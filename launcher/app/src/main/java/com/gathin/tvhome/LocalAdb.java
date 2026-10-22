package com.gathin.tvhome;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import javax.crypto.Cipher;

/** Small ADB-over-TCP client. Dedicated app-specific RSA key; TV must authorize it once. */
final class LocalAdb {
    private static final String TAG="LocalAdb";
    private static final int CNXN=0x4e584e43, AUTH=0x48545541, OPEN=0x4e45504f, OKAY=0x59414b4f, CLSE=0x45534c43, WRTE=0x45545257;
    private static final int VERSION=0x01000000;
    private final Context ctx;
    LocalAdb(Context c) {ctx=c.getApplicationContext();}

    private KeyPair getKey() throws Exception {
        android.content.SharedPreferences pref=ctx.getSharedPreferences("local_adb",0);
        String priv=pref.getString("private",null), pub=pref.getString("public",null);
        KeyFactory f=KeyFactory.getInstance("RSA");
        if(priv!=null && pub!=null) return new KeyPair(f.generatePublic(new X509EncodedKeySpec(Base64.decode(pub,Base64.DEFAULT))),f.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv,Base64.DEFAULT))));
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        KeyPair pair=g.generateKeyPair();
        if(!pref.edit().putString("private",Base64.encodeToString(pair.getPrivate().getEncoded(),Base64.NO_WRAP)).putString("public",Base64.encodeToString(pair.getPublic().getEncoded(),Base64.NO_WRAP)).commit()) throw new IOException("ADB key not persisted");
        return pair;
    }
    private static byte[] fixedLittle(BigInteger v,int size) {
        byte[] a=v.toByteArray(), b=new byte[size];
        for(int i=0;i<size && i<a.length;i++) b[i]=a[a.length-1-i];
        return b;
    }
    private static byte[] publicKey(RSAPublicKey pub) {
        BigInteger n=pub.getModulus(), mod=BigInteger.ONE.shiftLeft(32);
        long lo=n.and(mod.subtract(BigInteger.ONE)).longValue();
        BigInteger inv=BigInteger.valueOf(lo).modInverse(mod).negate().mod(mod);
        ByteBuffer b=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64); b.putInt(inv.intValue());
        b.put(fixedLittle(n,256));
        b.put(fixedLittle(BigInteger.ONE.shiftLeft(4096).mod(n),256));
        b.putInt(pub.getPublicExponent().intValue());
        String s=Base64.encodeToString(b.array(),Base64.NO_WRAP)+" cleanyourtv@streamer\0";
        return s.getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] signature(PrivateKey k,byte[] digest) throws Exception {
        // ADB AUTH token already contains the SHA1 digest. Do NOT hash it again.
        byte[] prefix=new byte[]{0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        byte[] v=new byte[prefix.length+digest.length];
        System.arraycopy(prefix,0,v,0,prefix.length); System.arraycopy(digest,0,v,prefix.length,digest.length);
        Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.ENCRYPT_MODE,k);
        return rsa.doFinal(v);
    }
    private static int check(byte[] buf) {int n=0;for(byte a:buf)n+=a&255;return n;}
    private static void send(OutputStream out,int cmd,int a0,int a1,byte[] data)throws IOException {
        if(data==null)data=new byte[0];
        ByteBuffer b=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(check(data)).putInt(cmd ^ -1);
        out.write(b.array()); out.write(data); out.flush();
    }
    private static class Packet {int cmd,a0,a1;byte[] data;}
    private static Packet recv(InputStream in) throws IOException {
        byte[] h=new byte[24];new DataInputStream(in).readFully(h);
        ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Packet p=new Packet();p.cmd=b.getInt();p.a0=b.getInt();p.a1=b.getInt();int len=b.getInt();int checksum=b.getInt(),magic=b.getInt();
        if(magic!=(p.cmd ^ -1)||len<0||len>1024*1024)throw new IOException("Invalid ADB header");
        p.data=new byte[len];new DataInputStream(in).readFully(p.data);
        if(check(p.data)!=checksum)throw new IOException("Invalid ADB checksum");
        return p;
    }
    void runShell(String command)throws Exception {
        runShell(command, false);
    }

    void runShellPersistent(String command)throws Exception {
        runShell(command, true);
    }

    private void runShell(String command, boolean persistent)throws Exception {
        KeyPair keys=getKey();
        Log.i(TAG, "Local ADB: key ready, connecting to loopback:5555");
        Socket socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",5555),2500);
        socket.setSoTimeout(12000);
        Log.i(TAG, "Local ADB: TCP connected");
        try {
            InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
            send(out,CNXN,VERSION,4096,"host::cleanyourtv\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed=false, sentKey=false,ready=false;
            for(int i=0;i<8;i++) {
                Packet p=recv(in);
                Log.i(TAG, "ADB handshake packet="+Integer.toHexString(p.cmd)+" arg0="+p.a0+" bytes="+p.data.length);
                if(p.cmd==CNXN){ready=true;break;}
                if(p.cmd!=AUTH || p.a0!=1) continue;
                if(!signed){Log.i(TAG,"ADB signing AUTH token");send(out,AUTH,2,0,signature(keys.getPrivate(),p.data));signed=true;}
                else if(!sentKey){Log.i(TAG,"ADB sending public key");send(out,AUTH,3,0,publicKey((RSAPublicKey)keys.getPublic()));sentKey=true;}
                else throw new IOException("Local ADB authorization pending; accept TV prompt");
            }
            if(!ready)throw new IOException("ADB handshake incomplete");
            Log.i(TAG,"ADB authenticated, executing shell");
            send(out,OPEN,1,0,("shell:"+command+"\0").getBytes(StandardCharsets.UTF_8));
            if (persistent) socket.setSoTimeout(0);
            int remote=-1;
            for(int i=0; persistent || i<20; i++) {
                Packet p=recv(in);
                if(p.cmd==OKAY) remote=p.a0;
                if(p.cmd==WRTE) send(out,OKAY,1,p.a0,null);
                if(p.cmd==CLSE) {
                    if (persistent) throw new IOException("Persistent ADB shell closed");
                    Log.i(TAG,"ADB shell completed");
                    return;
                }
            }
            if(remote>=0){Log.i(TAG,"ADB shell opened, no close yet");send(out,CLSE,1,remote,null);}
            else throw new IOException("ADB shell did not open");
        } finally {socket.close();}
    }
}
