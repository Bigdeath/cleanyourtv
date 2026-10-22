#!/system/bin/sh
# CleanYourTV local remote watcher; run as ADB shell (not app UID).
SELF=/data/local/tmp/tv-buttons.sh
PIDFILE=/data/local/tmp/tv-buttons.pid
LOG=/data/local/tmp/tv-buttons.log

running() {
    [ -s "$PIDFILE" ] || return 1
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    [ -r "/proc/$pid/cmdline" ] || return 1
    tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null |
        grep -Fq "$SELF daemon"
}

case "$1" in
    start)
        if running; then exit 0; fi
        /system/bin/nohup /system/bin/setsid /system/bin/sh "$SELF" daemon </dev/null >>"$LOG" 2>&1 &
        echo $! > "$PIDFILE"
        exit 0 ;;
    daemon) ;;
    *) exit 2 ;;
esac

CFG=/data/local/tmp/tv-buttons.conf
resolve_action() {
  name="$1"
  value="$(grep -m 1 "^${name}=" "$CFG" 2>/dev/null)"
  value="${value#*=}"
  [ -n "$value" ] || value=default
  case "$value" in
    default|none) return ;;
    package:*) pkg="${value#package:}"; echo "$name -> $pkg"; am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p "$pkg" ;;
    */*) echo "$name -> $value"; am start -n "$value" ;;
    *) echo "Invalid mapping for $name" ;;
  esac
}
while :; do
  DEVICE="$(getevent -pl 2>/dev/null | awk '/^add device/ {device=$4} /name: *"Google TV Remote Keyboard"/ {print device; exit}')"
  if [ -z "$DEVICE" ] || [ ! -e "$DEVICE" ]; then
    echo "Remote unavailable; retrying"
    sleep 1
    continue
  fi
  echo "Remote detected: $DEVICE"
  getevent -lt "$DEVICE" | while IFS= read -r line; do
    case "$line" in
      *MSC_SCAN*000c0077*) key=youtube ;;
      *MSC_SCAN*000c0186*) key=star ;;
      *MSC_SCAN*000c0078*) key=netflix ;;
      *MSC_SCAN*) key='' ;;
      *EV_KEY*UP*) [ -n "$key" ] && resolve_action "$key"; key='' ;;
    esac
  done
  echo "Remote disconnected; retrying"
  sleep 2
done
