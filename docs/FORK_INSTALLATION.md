# Extended fork: installation and setup

This guide applies to the extended fork at https://github.com/Bigdeath/cleanyourtv.
For the original CleanYourTV installation and recovery process, see the upstream
[README](https://github.com/Gathin23/cleanyourtv#readme). Review upstream device-specific
warnings before modifying any stock launcher components.

## 1. Install the launcher APK

Download the APK from this fork's [Releases](https://github.com/Bigdeath/cleanyourtv/releases)
when a release is available, or build the `launcher` module locally:

```sh
cd launcher
./gradlew assembleRelease --no-daemon --max-workers=1 --no-parallel
```

Connect from a computer with Android platform-tools and ADB over TCP already configured:

```sh
adb connect YOUR_TV_IP:5555
adb -s YOUR_TV_IP:5555 install -r launcher/app/build/outputs/apk/release/app-release.apk
```

Do not uninstall the original home app without following upstream recovery instructions.
Installing this fork does not by itself install or activate the optional ADB-based watchers.

## 2. Optional remote-button mapping

This feature was tested on **Google TV Streamer / Android 14**, using a
**Google TV Remote Keyboard** input device. Other remotes and firmware can use
different event names or scan codes and may not work.

The remote watcher runs as the Android ADB `shell` user, not as the launcher's
ordinary app user. Enable ADB debugging and ADB over TCP on your own device,
and ensure your computer is authorized. From the repository root:

```sh
adb -s YOUR_TV_IP:5555 push buttons/tv-buttons.sh /data/local/tmp/tv-buttons.sh
adb -s YOUR_TV_IP:5555 shell chmod 755 /data/local/tmp/tv-buttons.sh
adb -s YOUR_TV_IP:5555 shell sh /data/local/tmp/tv-buttons.sh start
```

Open the fork's launcher settings on the TV and select installed apps for the
YouTube, Star, or Netflix buttons. The app writes mappings to
`/data/local/tmp/tv-buttons.conf` via its own local ADB connection. On first
use, authorize **the launcher's separate ADB identity** in the device's
on-screen ADB dialog. Authorizing your computer alone does not authorize the
launcher. Keep the launcher active through that initial authorization.

The launcher includes a foreground supervisor and a boot receiver designed to
restart installed watchers after device reboot. ADB TCP, its authorization, and
the installed scripts must remain available. This is **not guaranteed** across
all Android TV firmware.

The Netflix key may also launch Netflix through a system-level action; this
implementation cannot universally suppress that original behavior. The button
watcher does not use Accessibility filtering, so it does not intentionally
intercept volume key events.

## 3. Optional Cast recovery watcher

This is **only** for devices where disabling
`com.google.android.tungsten.setupwraith` breaks subsequent Google Cast
sessions. It temporarily enables and then re-disables that package when the
Cast process restarts. It is **not** a generic Cast fix, and the package must
not be disabled on a device that needs it for other functions.

If you have diagnosed this exact issue and understand the workaround:

```sh
adb -s YOUR_TV_IP:5555 push cast/cast-local-watch.sh /data/local/tmp/cast-local-watch.sh
adb -s YOUR_TV_IP:5555 shell chmod 755 /data/local/tmp/cast-local-watch.sh
adb -s YOUR_TV_IP:5555 shell sh /data/local/tmp/cast-local-watch.sh start
```

The watcher and the launcher supervisor use ADB `shell` permissions. For other
TV models, leave this workaround uninstalled. Review the script before using it.

## 4. HTTP/HTTPS wallpapers

In launcher settings, configure the live wallpaper image endpoint and choose a
refresh interval and crossfade duration. Immich Kiosk-style direct image URLs
are supported. An endpoint must return an image; HTML gallery pages will not
work. Ensure the TV can reach the endpoint, and prefer HTTPS for connections
outside your local network.

The app loads images while Home is visible. URLs configured through the TV are
stored in the app's preferences. Do not commit personal image-service URLs,
authentication parameters, exported app data, or screenshots revealing private
content to a public repository.

## 5. Security and limitations

- **ADB over TCP grants powerful debugging capabilities**. Do not expose TCP
  port 5555 to the internet or untrusted Wi-Fi networks.
- The launcher creates its own ADB authentication key. Never distribute the
  private key or commit ADB credentials, local configs, or personal URLs.
- `/data/local/tmp` scripts are device-side prerequisites; they are **not
  packaged in the APK**.
- Disabling or updating the launcher or resetting ADB authorization may stop
  the background watchers. Test reboot behavior on your own device.
- Original setup and recovery instructions remain the upstream project's
  responsibility; follow them for your exact model.

## Troubleshooting

```sh
adb -s YOUR_TV_IP:5555 shell ls -l /data/local/tmp/tv-buttons.sh /data/local/tmp/cast-local-watch.sh
adb -s YOUR_TV_IP:5555 shell cat /data/local/tmp/tv-buttons.conf
adb -s YOUR_TV_IP:5555 shell pidof com.gathin.tvhome
```

A missing script, missing local-ADB authorization, or a different remote input
device will prevent some features from working. Avoid publicly sharing logs
that contain private network addresses or service URLs.
