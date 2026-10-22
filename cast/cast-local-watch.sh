#!/system/bin/sh
# Starts only as ADB shell user; never runs under launcher app UID.
SELF=/data/local/tmp/cast-local-watch.sh
PIDFILE=/data/local/tmp/cast-local-watch.pid
STATE=/data/local/tmp/cast-local-watch.state
LOG=/data/local/tmp/cast-local-watch.log
PKG=com.google.android.tungsten.setupwraith

running() {
  [ -s "$PIDFILE" ] || return 1
  old="$(cat "$PIDFILE" 2>/dev/null)"
  case "$old" in ''|*[!0-9]*) return 1;; esac
  [ -r "/proc/$old/cmdline" ] || return 1
  tr '\000' ' ' < "/proc/$old/cmdline" 2>/dev/null | grep -Fq "$SELF daemon"
}
case "$1" in
  start)
    if running; then exit 0; fi
    nohup sh "$SELF" daemon </dev/null >>"$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    exit 0 ;;
  stop)
    if running; then kill "$(cat "$PIDFILE")" 2>/dev/null; fi
    pm disable-user --user 0 "$PKG" >/dev/null 2>&1
    exit 0 ;;
  daemon) ;;
  *) exit 2 ;;
esac
trap 'pm disable-user --user 0 "$PKG" >/dev/null 2>&1' EXIT HUP INT TERM
printf '%s local cast watcher started\n' "$(date)"
while :; do
  # Keep the remote button watcher alive as well.
  # Its start command checks the existing PID before launching.
  sh /data/local/tmp/tv-buttons.sh start >/dev/null 2>&1

  boot="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
  castpid="$(pidof com.google.android.apps.mediashell 2>/dev/null | awk '{print $1}')"
  if [ -n "$boot" ] && [ -n "$castpid" ]; then
    current="$boot:$castpid"
    previous="$(cat "$STATE" 2>/dev/null)"
    if [ "$current" != "$previous" ]; then
      echo "$(date) cast process $castpid - temporarily enabling setupwraith"
      echo "$current" > "$STATE"
      pm enable --user 0 "$PKG" >/dev/null 2>&1
      sleep 10
      pm disable-user --user 0 "$PKG" >/dev/null 2>&1
      echo "$(date) setupwraith disabled"
    fi
  fi
  sleep 15
done
