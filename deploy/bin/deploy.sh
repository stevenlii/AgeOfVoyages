#!/bin/bash
#
# AgeOfVoyages 离线部署启动脚本（无需 Maven）
#   ./deploy.sh              不传参打印用法并退出
#   ./deploy.sh start        启动服务（先按 deploy.properties 替换配置/密钥，再启动）
#   ./deploy.sh stop         停止
#   ./deploy.sh restart      重启
#   ./deploy.sh logs         实时跟踪日志
#   ./deploy.sh status       查看运行状态
#   ./deploy.sh help         打印用法
#
# 前提：目标机器已安装 JDK 21+
#
# 配置/密钥替换：
#   conf/deploy.properties 声明了“包内文件(.current)”与“离线机实际文件(.runtime)”
#   的对应关系。start 启动时会把 .runtime 指向的文件复制覆盖到 .current 的位置
#   （config/env.yml、config/age-of-voyages.yml 等），从而无需把真实密钥打进包。

set -e

# ---------- 路径 ----------
BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
PKG_DIR="$(cd "$BIN_DIR/.." && pwd)"
BASE_DIR="$(cd "$PKG_DIR/.." && pwd)"
CONF_FILE="$PKG_DIR/conf/deploy.properties"
LOG_DIR="$PKG_DIR/logs"
PID_DIR="$PKG_DIR/logs"
JAR_DIR="$PKG_DIR/jars"
CONFIG_DIR="$PKG_DIR/config"

BACKEND_JAR="$JAR_DIR/backend-0.1.0.jar"
BACKEND_CONFIG="$CONFIG_DIR/age-of-voyages.yml"
ENV_YML="$CONFIG_DIR/env.yml"

BACKEND_PID="$PID_DIR/backend.pid"
BACKEND_PORT=8080

mkdir -p "$LOG_DIR"

# ---------- 工具函数 ----------
timestamp() { date "+%Y-%m-%d %H:%M:%S"; }

wait_for_port() {
  local port="$1" name="$2" max="${3:-120}"
  echo "[$(timestamp)] 等待 $name 端口 $port 就绪..."
  for ((i=0; i<max; i++)); do
    if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then
      exec 3>&- 3<&-
      echo "[$(timestamp)] $name 端口 $port 已就绪"
      return 0
    fi
    sleep 1
  done
  echo "[$(timestamp)] 警告：$name 在 ${max}s 内未就绪，请查看日志 $LOG_DIR/backend.log" >&2
  return 1
}

port_listener_pid() {
  local port="$1"
  command -v lsof >/dev/null 2>&1 || return 1
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -1
}

jar_pid() {
  local jar="$1"
  [ -n "$jar" ] || return 1
  pgrep -f "$jar" 2>/dev/null | head -1
}

live_pid() {
  local pid_file="$1" port="$2" jar="$3" pid
  if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    cat "$pid_file"
    return 0
  fi
  if pid="$(port_listener_pid "$port")" && [ -n "$pid" ]; then
    echo "$pid"; return 0
  fi
  if pid="$(jar_pid "$jar")" && [ -n "$pid" ]; then
    echo "$pid"; return 0
  fi
  return 1
}

stop_service() {
  local pid_file="$1" name="$2" port="$3" jar="$4" pid
  pid="$(live_pid "$pid_file" "$port" "$jar" || true)"
  if [ -n "$pid" ]; then
    echo "[$(timestamp)] 停止 $name (pid $pid)..."
    kill "$pid" 2>/dev/null || true
    for ((i=0; i<30; i++)); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
    echo "[$(timestamp)] $name 已停止"
  else
    echo "[$(timestamp)] $name 未在运行"
  fi
  rm -f "$pid_file"
}

kill_port_listeners() {
  local port="$1"
  command -v lsof >/dev/null 2>&1 || { echo "[$(timestamp)]   警告：未找到 lsof，跳过端口 $port 的残留清理"; return 0; }
  local pids
  pids="$(lsof -ti tcp:"$port" 2>/dev/null | tr '\n' ' ')"
  [ -z "$pids" ] && return 0
  echo "[$(timestamp)]   端口 $port 被残留进程占用 (pid: $pids)，发送 SIGTERM..."
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null || true
  local attempts
  for ((attempts=0; attempts<5; attempts++)); do
    pids="$(lsof -ti tcp:"$port" 2>/dev/null | tr '\n' ' ')"
    [ -z "$pids" ] && return 0
    sleep 1
  done
  pids="$(lsof -ti tcp:"$port" 2>/dev/null | tr '\n' ' ')"
  if [ -n "$pids" ]; then
    echo "[$(timestamp)]   端口 $port 仍被占用 (pid: $pids)，发送 SIGKILL..."
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
  fi
}

check_java() {
  if ! command -v java &>/dev/null; then
    echo "[ERROR] 未找到 java，请安装 JDK 21+ 后再运行此脚本" >&2
    exit 1
  fi
  local v
  v="$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)"
  if [ "${v:-0}" -lt 21 ]; then
    echo "[ERROR] 需要 JDK 21+，当前版本: $(java -version 2>&1 | head -1)" >&2
    exit 1
  fi
}

# 按 deploy.properties 替换配置
apply_env_override() {
  if [ ! -f "$CONF_FILE" ]; then
    echo "[$(timestamp)] 未找到配置文件 $CONF_FILE，跳过文件替换"
    return 0
  fi
  echo "[$(timestamp)] 读取配置文件 $CONF_FILE，替换部署包文件..."
  local bases=() currents=()
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    [ -z "${line// }" ] && continue
    case "$line" in
      *.current=*)
        bases+=("${line%%.current=*}")
        currents+=("${line#*.current=}")
        ;;
    esac
  done < "$CONF_FILE"
  local i base current runtime runtime_abs current_abs
  for ((i=0; i<${#bases[@]}; i++)); do
    base="${bases[$i]}"
    current="${currents[$i]}"
    runtime="$(grep -E "^[[:space:]]*${base}\.runtime[[:space:]]*=" "$CONF_FILE" | head -1 | sed -E 's/^[^=]*=[[:space:]]*//' || true)"
    if [ -z "$runtime" ]; then
      echo "[$(timestamp)]   跳过 $base：未找到 ${base}.runtime 配置"
      continue
    fi
    case "$current" in
      /*) current_abs="$current" ;;
      *)  current_abs="$PKG_DIR/$current" ;;
    esac
    case "$runtime" in
      "~"|"~/"*) runtime_abs="$HOME${runtime#\~}" ;;
      *)         runtime_abs="$runtime" ;;
    esac
    if [ "$runtime_abs" = "$current_abs" ]; then
      echo "[$(timestamp)]   跳过 $current：同一文件，无需复制"
      continue
    fi
    if [ -f "$runtime_abs" ]; then
      cp -f "$runtime_abs" "$current_abs"
      echo "[$(timestamp)]   已用 $runtime_abs 覆盖 $current"
    else
      echo "[$(timestamp)]   警告：$runtime_abs 不存在，跳过 $current 替换"
    fi
  done
}

do_status() {
  echo "=== 服务状态 ==="
  local pid uptime
  pid="$(live_pid "$BACKEND_PID" "$BACKEND_PORT" "$BACKEND_JAR" || true)"
  if [ -n "$pid" ]; then
    uptime="$(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ')"
    if (exec 3<>/dev/tcp/127.0.0.1/$BACKEND_PORT) 2>/dev/null; then
      exec 3>&- 3<&-
      echo "  ✅ age-of-voyages  pid=$pid  运行时长=${uptime:-unknown}  port=$BACKEND_PORT  已就绪"
    else
      echo "  ⚠️  age-of-voyages  pid=$pid  运行时长=${uptime:-unknown}  port=$BACKEND_PORT  进程在但端口未就绪"
    fi
  else
    echo "  ❌ age-of-voyages  未运行  port=$BACKEND_PORT"
  fi
}

confirm_restart_if_running() {
  NEED_STOP=0
  local pid
  pid="$(live_pid "$BACKEND_PID" "$BACKEND_PORT" "$BACKEND_JAR" || true)"
  if [ -z "$pid" ]; then return 0; fi
  echo "[$(timestamp)] 检测到服务已在运行 (pid=$pid, port=$BACKEND_PORT)"
  echo "[$(timestamp)] 是否停掉并重新启动？(y/N)"
  local ans=""
  if [ -t 0 ]; then
    stty icanon echo icrnl 2>/dev/null || true
    read -r -t 120 -p "请输入 y 确认重启 / 其它键取消: " ans
  fi
  ans="$(printf '%s' "$ans" | tr '[:upper:]' '[:lower:]')"
  case "$ans" in
    y|yes) NEED_STOP=1; return 0 ;;
    *) echo "[$(timestamp)] 已取消，保持现有服务运行。"; return 1 ;;
  esac
}

usage() {
  cat <<EOF
用法: $0 <命令>

  start     启动服务（先按 conf/deploy.properties 替换配置/密钥，再启动）
  stop      停止服务
  restart   重启（= stop + start）
  logs      实时跟踪日志
  status    查看运行状态

示例：
  $0 start
  $0 status
EOF
}

case "${1:-}" in
  "") usage; exit 1 ;;
  start)
    if ! confirm_restart_if_running; then
      exit 0
    fi
    check_java
    apply_env_override

    if [ ! -f "$BACKEND_JAR" ]; then
      echo "[ERROR] 未找到可执行 jar，请确认 jars/ 目录存在且包含 backend-0.1.0.jar" >&2
      exit 1
    fi

    if [ "$NEED_STOP" = 1 ]; then
      stop_service "$BACKEND_PID" "age-of-voyages" "$BACKEND_PORT" "$BACKEND_JAR"
      sleep 2
      kill_port_listeners "$BACKEND_PORT"
      sleep 1
    fi

    echo "[$(timestamp)] 启动 age-of-voyages -> $LOG_DIR/backend.log"
    nohup java -jar "$BACKEND_JAR" \
      --spring.config.additional-location="optional:file:$ENV_YML,file:$BACKEND_CONFIG" \
      > "$LOG_DIR/backend.log" 2>&1 &
    disown
    echo $! > "$BACKEND_PID"
    wait_for_port "$BACKEND_PORT" "age-of-voyages" 120 || true

    echo ""
    echo "==== 启动完成 ===="
    echo "  Web UI       : http://127.0.0.1:$BACKEND_PORT/"
    echo "  WebSocket    : ws://127.0.0.1:$BACKEND_PORT/ws"
    echo "  日志目录     : $LOG_DIR"
    echo "  查看状态     : $0 status"
    echo ""
    echo "==== 启动后状态 ===="
    do_status
    ;;

  stop)
    stop_service "$BACKEND_PID" "age-of-voyages" "$BACKEND_PORT" "$BACKEND_JAR"
    ;;

  restart)
    "$BIN_DIR/deploy.sh" stop
    sleep 3
    "$BIN_DIR/deploy.sh" start
    ;;

  logs)
    if command -v tail >/dev/null 2>&1; then
      tail -F "$LOG_DIR/backend.log"
    else
      echo "tail 不可用" >&2; exit 1
    fi
    ;;

  status)
    do_status
    ;;

  help|--help|-h)
    usage
    ;;

  *)
    echo "未知命令: ${1:-（空）}"; echo ""
    usage
    exit 1
    ;;
esac
