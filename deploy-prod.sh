#!/bin/bash
set -e

# ==========================================
#  FlowOps 生产部署脚本
#  用法: bash deploy-prod.sh <jar包名> [profile] [env文件]
#  示例: bash deploy-prod.sh flowops-app-1.1.0.jar
#         bash deploy-prod.sh app.jar prod .env.prod
#         bash deploy-prod.sh app.jar local .env.local
#
#  端口：
#    PORT             控制台 HTTP 宿主机端口，默认 8880 → 容器 8080
#    NEXA_PORT        主从通信宿主机发布端口，默认同 NEXA_MASTER_PORT
#    NEXA_MASTER_PORT 容器内监听端口，默认 8081（与 application.yml 一致），映射为 NEXA_PORT:NEXA_MASTER_PORT
#  容器内监听地址由 NEXA_MASTER_HOST 决定，脚本默认 0.0.0.0，
#  以便其他机器上的子节点连接；实际暴露范围由上面的端口映射与防火墙决定。
#  注意：这三个键都由本脚本用 -e 注入（优先级高于 env 文件），无需重复写进 env 文件。
#  示例: PORT=8880 NEXA_PORT=8081 bash deploy-prod.sh app.jar prod .env.prod
# ==========================================

IMAGE_NAME="flowops"
CONTAINER_NAME="flowops"
PORT="${PORT:-8880}"
# 主节点与子节点通信（Nexa Protocol Master）
NEXA_MASTER_HOST="${NEXA_MASTER_HOST:-0.0.0.0}"
NEXA_MASTER_PORT="${NEXA_MASTER_PORT:-8081}"
NEXA_PORT="${NEXA_PORT:-$NEXA_MASTER_PORT}"
DATA_DIR="/data/flowops"
APP_DIR="/app/flowops"
PROFILE="${2:-prod}"
ENV_NAME="${3:-.env.$PROFILE}"

echo "=========================================="
echo "  FlowOps 生产部署"
echo "=========================================="

# 检查参数
if [ -z "$1" ]; then
    echo "[ERROR] 请指定 JAR 文件名"
    echo "用法: bash deploy-prod.sh <jar包名> [profile]"
    exit 1
fi

JAR_FILE="$1"

# 检查 JAR 文件是否存在
if [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] JAR 文件不存在: $JAR_FILE"
    exit 1
fi

# 获取 JAR 绝对路径
JAR_FILE="$(cd "$(dirname "$JAR_FILE")" && pwd)/$(basename "$JAR_FILE")"

# 检查 Docker
if ! command -v docker &>/dev/null; then
    echo "[ERROR] 未检测到 Docker，请先安装 Docker"
    exit 1
fi

# 检查环境变量文件
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/$ENV_NAME"
if [ ! -f "$ENV_FILE" ]; then
    echo "[ERROR] 未找到 $ENV_NAME 文件"
    echo "请复制 .env.prod.example 为 $ENV_NAME 并填入真实配置"
    exit 1
fi

# 确保 APP 目录存在
mkdir -p "$APP_DIR"

# 复制 JAR 到 APP 目录并重命名
echo "[0/4] 复制 JAR: $(basename "$JAR_FILE") -> $APP_DIR/app.jar"
cp -f "$JAR_FILE" "$APP_DIR/app.jar"

if [ ! -f "$APP_DIR/app.jar" ]; then
    echo "[ERROR] JAR 复制失败"
    exit 1
fi

# 创建数据目录
mkdir -p "$DATA_DIR/services" "$DATA_DIR/logs"

# 停止并删除旧容器
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "[1/4] 停止旧容器..."
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm "$CONTAINER_NAME" 2>/dev/null || true
    echo "      旧容器已移除"
else
    echo "[1/4] 无需清理旧容器"
fi

# 构建镜像
echo "[2/4] 构建镜像..."
cd "$APP_DIR"
docker build -f Dockerfile -t "$IMAGE_NAME:latest" .

# 启动容器
echo "[3/4] 启动容器（profile: $PROFILE）..."
# 配置来源（实测优先级 高 → 低，完整说明见 docs/configuration-loading-order.md）：
#   命令行参数  >  JVM 系统属性  >  -e / --env-file 环境变量  >  .env.<profile> 文件  >  application-<profile>.yml  >  application.yml
# 每个键独立沿该链查找，命中即止。--env-file 与本脚本的 -e 都落在第 3 层，
# 同一键两处都写时以 -e 为准（docker 规则），因此 NEXA_MASTER_HOST / NEXA_MASTER_PORT /
# SPRING_PROFILES_ACTIVE 由本脚本 -e 注入即可，无需重复写进 env 文件。
# 容器内的 profile 由 SPRING_PROFILES_ACTIVE 决定，应用据此加载挂载的 .env.<profile>。
docker run -d \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    -p "$PORT:8080" \
    -p "$NEXA_PORT:$NEXA_MASTER_PORT" \
    -e "SPRING_PROFILES_ACTIVE=$PROFILE" \
    -e "NEXA_MASTER_HOST=$NEXA_MASTER_HOST" \
    -e "NEXA_MASTER_PORT=$NEXA_MASTER_PORT" \
    --env-file "$ENV_FILE" \
    -v "$ENV_FILE:/app/$ENV_NAME:ro" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$DATA_DIR:/data/flowops" \
    "$IMAGE_NAME:latest"

# 等待启动
echo "[4/4] 等待服务启动..."
sleep 3

if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    SERVER_IP=$(hostname -I | awk '{print $1}')
    echo ""
    echo "=========================================="
    echo "  部署成功!"
    echo "  控制台:     http://$SERVER_IP:$PORT/login"
    echo "  子节点通信: $SERVER_IP:$NEXA_PORT (Nexa Protocol Master)"
    echo "  默认账号:   admin / admin123"
    echo "------------------------------------------"
    echo "  子节点接入前请确认："
    echo "   1) 防火墙/安全组已放通 $NEXA_PORT（容器内监听 $NEXA_MASTER_HOST:$NEXA_MASTER_PORT）"
    echo "   2) 已用超级管理员录入节点令牌：POST /api/nodes/registry"
    echo "      body: {\"runnerId\":\"runner-1\",\"nodeName\":\"节点1\",\"token\":\"<与子节点一致>\"}"
    echo "   3) 子节点配置同一 token 指向 $SERVER_IP:$NEXA_PORT"
    echo "=========================================="
else
    echo ""
    echo "[ERROR] 容器启动失败，查看日志:"
    docker logs "$CONTAINER_NAME" 2>/dev/null || true
    exit 1
fi
