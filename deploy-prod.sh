#!/bin/bash
set -e

# ==========================================
#  FlowOps 生产部署脚本
#  用法: bash deploy-prod.sh <jar包名> [profile]
#  示例: bash deploy-prod.sh flowops-app-1.1.0.jar
#         bash deploy-prod.sh app.jar dev
# ==========================================

IMAGE_NAME="flowops"
CONTAINER_NAME="flowops"
PORT="${PORT:-8880}"
DATA_DIR="/data/flowops"
APP_DIR="/app/flowops"
PROFILE="${2:-prod}"

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

# 检查 .env.prod 文件
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env.prod"
if [ ! -f "$ENV_FILE" ]; then
    echo "[ERROR] 未找到 .env.prod 文件"
    echo "请复制 .env.prod.example 为 .env.prod 并填入真实配置"
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
docker run -d \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    -p "$PORT:8080" \
    -e "SPRING_PROFILES_ACTIVE=$PROFILE" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$DATA_DIR:/data/flowops" \
    -v "$ENV_FILE:/app/.env.prod:ro" \
    "$IMAGE_NAME:latest"

# 等待启动
echo "[4/4] 等待服务启动..."
sleep 3

if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    SERVER_IP=$(hostname -I | awk '{print $1}')
    echo ""
    echo "=========================================="
    echo "  部署成功!"
    echo "  访问地址: http://$SERVER_IP:$PORT/login"
    echo "  默认账号: admin / admin123"
    echo "=========================================="
else
    echo ""
    echo "[ERROR] 容器启动失败，查看日志:"
    docker logs "$CONTAINER_NAME" 2>/dev/null || true
    exit 1
fi
