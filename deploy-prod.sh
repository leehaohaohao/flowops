#!/bin/bash
set -e

# ==========================================
#  FlowOps 生产部署脚本
#  用法: bash deploy-prod.sh <jar包名> [profile]
#  示例: bash deploy-prod.sh flowops-0.0.1-SNAPSHOT.jar
#         bash deploy-prod.sh flowops.jar dev
# ==========================================

IMAGE_NAME="flowops"
CONTAINER_NAME="flowops"
PORT="8880"
DATA_DIR="/data/flowops"
APP_DIR="/app/flowops"
PROFILE="${2:-prod}"   # 默认 prod，可通过第二个参数和覆盖

echo "=========================================="
echo "  FlowOps 生产部署"
echo "=========================================="

# 检查参数
if [ -z "$1" ]; then
    echo "[ERROR] 请指定 JAR 文件名"
    echo "用法: bash deploy-prod.sh <jar包名>"
    exit 1
fi

JAR_FILE="$1"

# 检查 Docker
if ! command -v docker &>/dev/null; then
    echo "[ERROR] 未检测到 Docker，请先安装 Docker"
    exit 1
fi

# 确保 APP 目录存在
mkdir -p "$APP_DIR"

# 复制 JAR 到 APP 目录并重命名为 flowops.jar（Dockerfile 期望的文件名）
echo "[0/4] 准备 JAR 文件..."
cp "$JAR_FILE" "$APP_DIR/flowops.jar"

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
docker build -f Dockerfile.prod -t "$IMAGE_NAME:latest" .

# 启动容器
echo "[3/4] 启动容器（profile: $PROFILE）..."
docker run -d \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    -p "$PORT:8080" \
    -e "SPRING_PROFILES_ACTIVE=$PROFILE" \
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
    echo "  访问地址: http://$SERVER_IP:$PORT/login"
    echo "  默认账号: admin / admin123"
    echo "=========================================="
else
    echo ""
    echo "[ERROR] 容器启动失败，查看日志:"
    docker logs "$CONTAINER_NAME" 2>/dev/null || true
    exit 1
fi
