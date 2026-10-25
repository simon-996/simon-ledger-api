#!/bin/bash
set -euo pipefail

APP_NAME="${APP_NAME:-simon_ledger}"
APP_PORT="${APP_PORT:-18080}"
HOST_PORT="${HOST_PORT:-$APP_PORT}"
CONTAINER_NAME="${CONTAINER_NAME:-${APP_NAME}-api}"
BASE_DIR="${BASE_DIR:-/apps}"
APP_HOME="$BASE_DIR/$APP_NAME"
APP_DIR="$APP_HOME/api"
LOG_DIR="$APP_HOME/logs"
PACKAGE_FILE="${PACKAGE_FILE:-$APP_HOME/app.tar.gz}"
ENV_FILE="${ENV_FILE:-$APP_HOME/secrets/api.env}"
RELEASE_ID="$(date +%Y%m%d%H%M%S)-$$"
DOCKER_IMAGE="${CONTAINER_NAME}:$RELEASE_ID"
BACKUP_CONTAINER="${CONTAINER_NAME}-previous-$RELEASE_ID"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:$HOST_PORT/health}"
HEALTH_ATTEMPTS="${HEALTH_ATTEMPTS:-60}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-2}"
TMP_DIR=""
OLD_SAVED=false
OLD_STOPPED=false
NEW_ATTEMPTED=false
SUCCESS=false

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖命令：$1" >&2; exit 1; }
}
container_exists() { docker container inspect "$1" >/dev/null 2>&1; }

on_exit() {
  local status=$?
  trap - EXIT
  if [[ "$SUCCESS" != true && ( "$NEW_ATTEMPTED" == true || "$OLD_STOPPED" == true ) ]]; then
    echo "新 API 未通过健康检查，恢复上一容器..."
    if [[ "$NEW_ATTEMPTED" == true ]]; then docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true; fi
    if [[ "$OLD_SAVED" == true ]]; then
      if docker rename "$BACKUP_CONTAINER" "$CONTAINER_NAME" && docker start "$CONTAINER_NAME"; then
        echo "上一容器已恢复；请检查健康状态：$HEALTH_URL"
      else
        echo "自动恢复失败，请手动恢复容器：$BACKUP_CONTAINER" >&2
      fi
    elif [[ "$OLD_STOPPED" == true ]]; then
      docker start "$CONTAINER_NAME" || true
    fi
  fi
  [[ -z "$TMP_DIR" || ! -d "$TMP_DIR" ]] || rm -rf "$TMP_DIR"
  if (( status != 0 )); then echo "API 部署失败，退出码：$status" >&2; fi
  exit "$status"
}
trap on_exit EXIT
for dependency in docker tar mktemp tee curl; do require_command "$dependency"; done
mkdir -p "$APP_HOME" "$LOG_DIR"
exec > >(tee -a "$LOG_DIR/deployment_api_log.txt") 2>&1

[[ -f "$PACKAGE_FILE" ]] || { echo "API 压缩包不存在：$PACKAGE_FILE"; exit 1; }
[[ -r "$ENV_FILE" ]] || { echo "API environment file missing or unreadable: $ENV_FILE"; exit 1; }
docker info >/dev/null
TMP_DIR=$(mktemp -d "$APP_HOME/.api-deploy.XXXXXX")
# Only expected, regular release files are extracted; no archive path is trusted.
tar -xzf "$PACKAGE_FILE" -C "$TMP_DIR" Dockerfile app.jar
[[ -f "$TMP_DIR/Dockerfile" && ! -L "$TMP_DIR/Dockerfile" && -f "$TMP_DIR/app.jar" && ! -L "$TMP_DIR/app.jar" ]] \
  || { echo "压缩包必须包含常规文件 Dockerfile 和 app.jar"; exit 1; }
docker build --build-arg APP_JAR=app.jar -t "$DOCKER_IMAGE" "$TMP_DIR"
# The image runs as uid 10001. Verify the actual bind mount before stopping the old API.
if ! docker run --rm --entrypoint sh -v "$LOG_DIR:/logs" "$DOCKER_IMAGE" -c 'test -w /logs'; then
  echo "应用日志目录必须对容器 uid 10001 可写：$LOG_DIR" >&2
  exit 1
fi

if container_exists "$CONTAINER_NAME"; then
  OLD_STOPPED=true
  docker stop "$CONTAINER_NAME"
  docker rename "$CONTAINER_NAME" "$BACKUP_CONTAINER"
  OLD_SAVED=true
fi
NEW_ATTEMPTED=true
docker run -d --env-file "$ENV_FILE" -p "$HOST_PORT:$APP_PORT" \
  -v "$LOG_DIR:/logs" --restart always --name "$CONTAINER_NAME" "$DOCKER_IMAGE"

healthy=false
for ((attempt=1; attempt<=HEALTH_ATTEMPTS; attempt++)); do
  if [[ "$(docker container inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || true)" != true ]]; then break; fi
  if health_body=$(curl --fail --silent --max-time 2 "$HEALTH_URL") \
    && [[ "$health_body" == *'"status":"up"'* && "$health_body" == *'"code":0'* ]]; then
    healthy=true
    break
  fi
  sleep "$HEALTH_INTERVAL"
done
[[ "$healthy" == true ]] || { echo "新 API 健康检查失败：$HEALTH_URL"; exit 1; }

# Preserve deployed files until the new container has passed the readiness gate.
if [[ -d "$APP_DIR" ]]; then mv "$APP_DIR" "$APP_HOME/api.previous.$RELEASE_ID"; fi
mv "$TMP_DIR" "$APP_DIR"
TMP_DIR=""
SUCCESS=true
if [[ "$OLD_SAVED" == true ]]; then docker rm "$BACKUP_CONTAINER" >/dev/null || true; fi
echo "API 部署完成，健康检查通过：$HEALTH_URL"
