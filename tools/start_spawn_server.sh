#!/usr/bin/env bash
# Towncraft Spawn Server - start/stop/restart/status
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="paper-1.21.4-232.jar"
PID_FILE="$SERVER_DIR/codex-spawn.pid"
LOG_FILE="$SERVER_DIR/logs/nohup.out"
JAVA="/opt/jenkins-agent-jdk21/bin/java"

# Load service env vars (e.g. ROOKIELLMAGENT_ROUTER_API_KEY for the RookieLLMAgent plugin).
if [ -f "$SERVER_DIR/router.env" ]; then
    . "$SERVER_DIR/router.env"
fi

# Aikar's flags (from Start.bat)
JAVA_OPTS=(
  -Xms2G -Xmx4G
  -XX:+UseG1GC
  -XX:+ParallelRefProcEnabled
  -XX:MaxGCPauseMillis=200
  -XX:+UnlockExperimentalVMOptions
  -XX:+DisableExplicitGC
  -XX:+AlwaysPreTouch
  -XX:G1HeapWastePercent=5
  -XX:G1MixedGCCountTarget=4
  -XX:G1MixedGCLiveThresholdPercent=90
  -XX:G1RSetUpdatingPauseTimePercent=5
  -XX:SurvivorRatio=32
  -XX:+PerfDisableSharedMem
  -XX:MaxTenuringThreshold=1
  -XX:G1NewSizePercent=30
  -XX:G1MaxNewSizePercent=40
  -XX:G1HeapRegionSize=8M
  -XX:G1ReservePercent=20
  -XX:InitiatingHeapOccupancyPercent=15
  -Dusing.aikars.flags=https://mcflags.emc.gs
  -Daikars.new.flags=true
  -Djavax.net.ssl.trustStore="$SERVER_DIR/rookievoice-truststore.p12"
  -Djavax.net.ssl.trustStorePassword=changeit
  -Djava.awt.headless=true
)

pid_state() {
  local pid="${1:-}"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  [[ -r "/proc/$pid/stat" ]] || return 1
  awk '{ print $3 }' "/proc/$pid/stat"
}

is_live_pid() {
  local pid="${1:-}"
  local state
  state=$(pid_state "$pid") || return 1
  [[ "$state" != "Z" ]] && kill -0 "$pid" 2>/dev/null
}

is_running() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid
  pid=$(<"$PID_FILE")
  is_live_pid "$pid"
}

stop_watchdog() {
  local watchdog_pid
  if [[ -f "$PID_FILE.watchdog" ]]; then
    watchdog_pid=$(<"$PID_FILE.watchdog")
    if is_live_pid "$watchdog_pid"; then
      kill "$watchdog_pid" 2>/dev/null || true
    fi
  fi
  rm -f "$PID_FILE.watchdog"
}

do_start() {
  if is_running; then
    echo "Server already running (PID $(<"$PID_FILE"))"
    return 0
  fi

  # A dead or zombie process must never block a restart.
  rm -f "$PID_FILE"
  stop_watchdog

  cd "$SERVER_DIR"
  echo "Starting $JAR ..."
  setsid "$JAVA" "${JAVA_OPTS[@]}" -jar "$JAR" nogui </dev/null >> "$LOG_FILE" 2>&1 &
  local pid=$!
  echo "$pid" > "$PID_FILE"
  echo "Server started (PID $pid), log: $LOG_FILE"

  # Start font fix watchdog.
  nohup bash "$SERVER_DIR/fix_font_watchdog.sh" </dev/null >> "$LOG_FILE" 2>&1 &
  local watchdog_pid=$!
  echo "$watchdog_pid" > "$PID_FILE.watchdog"
  echo "Font fix watchdog started (PID $watchdog_pid)"
}

do_fg() {
  # Foreground run for tmux: the pane becomes an interactive MC console
  # (stdin = server commands, stdout = live console). Same flags/env/watchdog/pid as daemon mode.
  if is_running; then
    echo "Server already running (PID $(<"$PID_FILE"))"
    exit 1
  fi

  rm -f "$PID_FILE"
  stop_watchdog

  cd "$SERVER_DIR"
  # Font fix watchdog (detached), same as daemon mode.
  nohup bash "$SERVER_DIR/fix_font_watchdog.sh" </dev/null >> "$LOG_FILE" 2>&1 &
  local watchdog_pid=$!
  echo "$watchdog_pid" > "$PID_FILE.watchdog"
  echo "Font fix watchdog started (PID $watchdog_pid)"

  # exec keeps this shell's pid for java, so stop/status (which read PID_FILE) stay consistent.
  echo "$$" > "$PID_FILE"
  echo "Starting $JAR in foreground (PID $$) ..."
  exec "$JAVA" "${JAVA_OPTS[@]}" -jar "$JAR" nogui
}

do_stop() {
  if ! is_running; then
    echo "Server is not running"
    rm -f "$PID_FILE"
    stop_watchdog
    return 0
  fi

  local pid
  pid=$(<"$PID_FILE")
  echo "Stopping server (PID $pid) ..."
  kill "$pid" 2>/dev/null || true

  for _ in $(seq 1 30); do
    if ! is_live_pid "$pid"; then
      echo "Server stopped."
      rm -f "$PID_FILE"
      stop_watchdog
      return 0
    fi
    sleep 1
  done

  echo "Force killing ..."
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  stop_watchdog
  echo "Server killed."
}

do_status() {
  if is_running; then
    echo "Server is running (PID $(<"$PID_FILE"))"
  else
    echo "Server is not running"
  fi
}

case "${1:-start}" in
  start)   do_start ;;
  tmux|fg) do_fg ;;
  stop)    do_stop ;;
  restart) do_stop; sleep 2; do_start ;;
  status)  do_status ;;
  *)
    echo "Usage: $0 {start|stop|restart|status|tmux}"
    exit 1
    ;;
esac
