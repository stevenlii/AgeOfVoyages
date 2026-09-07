#!/bin/bash
#
# AgeOfVoyages 本地构建 / 启动脚本（源码开发用）
#   ./build.sh                无参数时打印用法说明并退出（不会自动构建/启动）
#   ./build.sh start          构建并启动服务（前端 build + 后端 Maven 构建，前端产物并入 jar）
#   ./build.sh stop           停止服务
#   ./build.sh restart        重启（= stop + start）
#   ./build.sh logs           实时跟踪日志
#   ./build.sh deploy         构建并打包离线部署包 deploy.tar.gz
#   ./build.sh status         查看运行状态
#
# 环境变量：
#   SKIP_BUILD=1              跳过 Maven + 前端构建
#   SKIP_FRONTEND_BUILD=1     跳过前端 npm 构建
#   VUE_APP_WS_URL=ws://xxx   覆盖前端 WebSocket 地址

set -e

unset SERVER__PORT SERVER__HOST 2>/dev/null || true

# ---------- 路径 ----------
BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
PKG_DIR="$(cd "$BIN_DIR/.." && pwd)"
REPO_DIR="$(cd "$PKG_DIR/.." && pwd)"
LOG_DIR="$PKG_DIR/logs"

BACKEND_JAR="$REPO_DIR/backend/target/backend-0.1.0.jar"
BACKEND_PID="$LOG_DIR/backend.pid"
BACKEND_PORT=8080

WEB_DIR="$REPO_DIR/frontend"
NPM="$(command -v npm 2>/dev/null || true)"
if [ -z "$NPM" ]; then
  NODE_DIR="$(command -v node 2>/dev/null | xargs dirname 2>/dev/null || true)"
  [ -n "$NODE_DIR" ] && NPM="$NODE_DIR/npm"
fi
STATIC_DIR="$REPO_DIR/backend/src/main/resources/static"

CONF_FILE="$PKG_DIR/conf/deploy.properties"
CONFIG_DIR="$PKG_DIR/config"
BACKEND_CONFIG="$CONFIG_DIR/age-of-voyages.yml"
ENV_YML="$CONFIG_DIR/env.yml"

mkdir -p "$LOG_DIR"

# ---------- 工具函数 ----------
timestamp() { date "+%Y-%m-%d %H:%M:%S"; }

wait_for_port() {
  local port="$1" name="$2" max="${3:-60}"
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

live_pid() {
  local pid_file="$1" port="$2" jar="$3" pid
  if [ -f "$pid_file" ]; then
    pid="$(cat "$pid_file" 2>/dev/null)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      echo "$pid"; return 0
    fi
  fi
  pid="$(port_listener_pid "$port")"
  [ -n "$pid" ] && { echo "$pid"; return 0; }
  if [ -n "$jar" ]; then
    pid="$(jar_pid "$jar")"
    [ -n "$pid" ] && { echo "$pid"; return 0; }
  fi
  return 1
}

port_listener_pid() {
  lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1
}

jar_pid() {
  local pid
  pid="$(pgrep -f "$1" 2>/dev/null | head -1)"
  if [ -n "$pid" ]; then echo "$pid"; return 0; fi
  pid="$(ps -eo pid,command 2>/dev/null | grep -F "$1" | grep -v grep | awk '{print $1}' | head -1)"
  [ -n "$pid" ] && { echo "$pid"; return 0; }
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
    rm -f "$pid_file"
    echo "[$(timestamp)] $name 已停止"
  else
    echo "[$(timestamp)] $name 未在运行"
    rm -f "$pid_file"
  fi
}

# 构建前端并拷入后端 static/
build_frontend() {
  if [ -n "$SKIP_FRONTEND_BUILD" ]; then
    echo "[$(timestamp)] 跳过前端构建（SKIP_FRONTEND_BUILD=1）"
    return 0
  fi
  if [ ! -d "$WEB_DIR" ]; then
    echo "[$(timestamp)] 未找到前端目录 $WEB_DIR，跳过前端构建" >&2
    return 0
  fi
  if [ -z "$NPM" ]; then
    echo "未找到 npm，无法构建前端。请安装 Node.js，或设置 SKIP_FRONTEND_BUILD=1 跳过" >&2
    exit 1
  fi
  echo "[$(timestamp)] 构建前端 ($NPM)..."
  ( cd "$WEB_DIR" && "$NPM" install --no-audit --no-fund && "$NPM" run build )
  if [ ! -d "$WEB_DIR/dist" ]; then
    echo "前端构建失败：未生成 $WEB_DIR/dist" >&2
    exit 1
  fi
  echo "[$(timestamp)] 拷贝前端产物到后端 static/..."
  rm -rf "$STATIC_DIR"
  mkdir -p "$STATIC_DIR"
  cp -R "$WEB_DIR/dist/." "$STATIC_DIR/"
  echo "[$(timestamp)] 前端产物已并入后端 jar"
}

# 从源码拷贝 application.yml 到 deploy/config/
sync_config_from_source() {
  mkdir -p "$CONFIG_DIR"
  cp -f "$REPO_DIR/backend/src/main/resources/application.yml" "$PKG_DIR/config/age-of-voyages.yml"
  echo "[$(timestamp)] 已从源码拷贝 application.yml 到 $CONFIG_DIR/"
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

# git 更新
git_update() {
  if [ -n "$SKIP_BUILD" ]; then
    echo "[$(timestamp)] 跳过 git 更新（SKIP_BUILD=1）"
    return 0
  fi
  if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    local branch upstream before after
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
    echo "[$(timestamp)] 当前分支: ${branch:-未知}"
    upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)"
    if [ -n "$upstream" ]; then
      echo "[$(timestamp)] 拉取 $upstream 最新代码..."
      before="$(git rev-parse HEAD 2>/dev/null || true)"
      git pull --ff-only || { echo "git pull 失败" >&2; exit 1; }
      after="$(git rev-parse HEAD 2>/dev/null || true)"
      if [ "$before" = "$after" ]; then
        echo "[$(timestamp)] 已是最新"
      else
        echo "[$(timestamp)] 已拉取新提交（$(echo "$before" | cut -c1-8) -> $(echo "$after" | cut -c1-8)）"
      fi
    else
      echo "[$(timestamp)] 当前分支未设置上游跟踪，跳过 git pull"
    fi
  else
    echo "[$(timestamp)] 未检测到 git，跳过代码更新"
  fi
}

warn_uncommitted() {
  command -v git >/dev/null 2>&1 || return 0
  git -C "$REPO_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1 || return 0
  local st n_mod n_untracked ahead behind has_upstream dirty=0
  st="$(git -C "$REPO_DIR" status --porcelain 2>/dev/null)"
  if [ -n "$st" ]; then
    n_mod="$(printf '%s\n' "$st" | grep -cE '^[ MADRC]' || true)"
    n_untracked="$(printf '%s\n' "$st" | grep -cE '^\?\?' || true)"
  else
    n_mod=0; n_untracked=0
  fi
  has_upstream=0; ahead=0; behind=0
  if git -C "$REPO_DIR" rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then
    has_upstream=1
    ahead="$(git -C "$REPO_DIR" rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)"
    behind="$(git -C "$REPO_DIR" rev-list --count 'HEAD..@{u}' 2>/dev/null || echo 0)"
  fi
  [ -n "$st" ] && dirty=1
  [ "${ahead:-0}" -gt 0 ] 2>/dev/null && dirty=1
  if [ "$dirty" = 0 ] && [ "${behind:-0}" -eq 0 ] 2>/dev/null; then
    return 0
  fi
  echo ""
  echo "⚠️  ⚠️  ⚠️  ================================================================"
  if [ -n "$st" ]; then
    echo "⚠️  仓库存在【未提交】的本地改动（已修改/已暂存 $n_mod 个，未跟踪 $n_untracked 个）"
  fi
  if [ "${ahead:-0}" -gt 0 ] 2>/dev/null; then
    echo "⚠️  仓库有 $ahead 个本地提交尚未 push 到远程"
  fi
  if [ "${behind:-0}" -gt 0 ] 2>/dev/null; then
    echo "⚠️  远程领先本地 $behind 个提交"
  fi
  if [ "$has_upstream" = 0 ]; then
    echo "⚠️  当前分支未设置上游跟踪"
  fi
  echo "⚠️  ================================================================"
  echo ""
}

confirm_restart_if_running() {
  NEED_STOP=0
  local pid
  pid="$(live_pid "$BACKEND_PID" "$BACKEND_PORT" "$BACKEND_JAR" || true)"
  if [ -z "$pid" ]; then return 0; fi
  echo "[$(timestamp)] 检测到后端服务已在运行 (pid=$pid, port=$BACKEND_PORT)"
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

do_start() {
  if ! confirm_restart_if_running; then
    return 0
  fi
  if [ "$NEED_STOP" = 1 ]; then
    stop_service "$BACKEND_PID" "age-of-voyages" "$BACKEND_PORT" "$BACKEND_JAR"
    sleep 2
  fi
  git_update
  sync_config_from_source
  apply_env_override

  if [ -z "$SKIP_BUILD" ]; then
    build_frontend
    echo "[$(timestamp)] 构建后端 ($MVN)..."
    "$MVN" -f "$REPO_DIR/backend/pom.xml" clean package -DskipTests -q
    echo "[$(timestamp)] 构建完成"
  fi

  if [ ! -f "$BACKEND_JAR" ]; then
    echo "未找到可执行 jar，请先构建（去掉 SKIP_BUILD=1）" >&2
    exit 1
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
  echo "  Web UI     : http://127.0.0.1:$BACKEND_PORT/"
  echo "  WebSocket  : ws://127.0.0.1:$BACKEND_PORT/ws"
  echo "  日志目录   : $LOG_DIR"
  echo "  查看状态   : $0 status"
  echo ""
  do_status
  warn_uncommitted
}

do_stop() {
  stop_service "$BACKEND_PID" "age-of-voyages" "$BACKEND_PORT" "$BACKEND_JAR"
}

do_logs() {
  if command -v tail >/dev/null 2>&1; then
    tail -F "$LOG_DIR/backend.log"
  else
    echo "tail 不可用" >&2; exit 1
  fi
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

# 优先用 PATH 里的 mvn，否则回退到 IDEA 自带 Maven
MVN="$(command -v mvn 2>/dev/null || true)"
if [ -z "$MVN" ] && [ -x "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" ]; then
  MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
fi
if [ -z "$MVN" ]; then
  echo "未找到 mvn，请先将其加入 PATH 或安装 Maven" >&2
  exit 1
fi

do_deploy() {
  git_update
  if [ -z "$SKIP_BUILD" ]; then
    build_frontend
    echo "[$(timestamp)] 构建 ($MVN)..."
    "$MVN" -f "$REPO_DIR/backend/pom.xml" clean package -DskipTests -q
  fi
  if [ ! -f "$BACKEND_JAR" ]; then
    echo "未找到可执行 jar，请先构建" >&2
    exit 1
  fi
  if [ ! -f "$PKG_DIR/bin/deploy.sh" ]; then
    echo "缺少离线启动脚本 $PKG_DIR/bin/deploy.sh" >&2
    exit 1
  fi

  echo "[$(timestamp)] 组装离线部署包..."
  mkdir -p "$PKG_DIR/jars"
  cp -f "$BACKEND_JAR" "$PKG_DIR/jars/"
  sync_config_from_source
  chmod +x "$PKG_DIR/bin/deploy.sh"

  local RELEASE_DIR="$PKG_DIR/release"
  mkdir -p "$RELEASE_DIR"
  local VER="$(date +%Y%m%d-%H%M%S)"
  local PKG_NAME="deploy-${VER}.tar.gz"
  local TRANSFORM=()
  if tar --version 2>/dev/null | grep -qi gnu; then
    TRANSFORM=(--transform "s,^deploy,deploy-${VER},")
  else
    TRANSFORM=(-s "/^deploy/deploy-${VER}/")
  fi
  tar -czvf "$RELEASE_DIR/$PKG_NAME" "${TRANSFORM[@]}" \
    --exclude='deploy/logs' \
    --exclude='deploy/bin/build.sh' \
    --exclude='deploy/.env' \
    --exclude='deploy/config/env.yml' \
    --exclude='deploy/release' \
    -C "$REPO_DIR" deploy
  echo ""
  echo "==== 部署包已生成 ===="
  echo "  $RELEASE_DIR/$PKG_NAME"
  echo "  解压后执行：tar -xzvf $PKG_NAME && cd deploy-${VER} && ./bin/deploy.sh start"
  warn_uncommitted
}

usage() {
  cat <<EOF
用法: $0 <命令>

  start     构建并启动服务（前端 build + 后端 mvn 构建 + 启动）
  stop      停止服务
  restart   重启（= stop + start）
  logs      实时跟踪日志
  status    查看服务状态
  deploy    打包离线部署包 deploy.tar.gz

环境变量：
  SKIP_BUILD=1            跳过构建，直接启动
  SKIP_FRONTEND_BUILD=1   跳过前端构建

示例：
  $0 start
  SKIP_BUILD=1 $0 start
  $0 deploy
EOF
}

case "${1:-}" in
  "") usage; exit 1 ;;
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_stop; sleep 2; do_start ;;
  logs)    do_logs ;;
  deploy)  do_deploy ;;
  status)  do_status ;;
  *) echo "未知命令: $1"; echo ""; usage; exit 1 ;;
esac
