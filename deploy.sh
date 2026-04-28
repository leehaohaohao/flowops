#!/bin/bash
set -e

IMAGE_NAME="flowops"
CONTAINER_NAME="flowops"
PORT="8080"
DATA_DIR="/data/flowops"

echo "=========================================="
echo "  FlowOps 一键部署脚本"
echo "=========================================="

# 检查 Docker
if ! command -v docker &>/dev/null; then
    echo "[ERROR] 未检测到 Docker，请先安装 Docker"
    exit 1
fi

# 创建数据目录
mkdir -p "$DATA_DIR/services" "$DATA_DIR/logs"

# 停止并删除旧容器
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "[1/3] 停止旧容器..."
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm "$CONTAINER_NAME" 2>/dev/null || true
    echo "      旧容器已移除"
else
    echo "[1/3] 无需清理旧容器"
fi

# 构建镜像
echo "[2/3] 构建镜像..."
docker build -t "$IMAGE_NAME:latest" .

# 启动容器
echo "[3/3] 启动容器..."
docker run -d \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    -p "$PORT:8080" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$DATA_DIR:/data/flowops" \
    "$IMAGE_NAME:latest"

# 等待启动
echo ""
echo "等待服务启动..."
sleep 3

if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo ""
    echo "=========================================="
    echo "  部署成功!"
    echo "  访问地址: http://localhost:$PORT/login"
    echo "  默认账号: admin / admin123"
    echo "=========================================="
else
    echo ""
    echo "[ERROR] 容器启动失败，查看日志:"
    docker logs "$CONTAINER_NAME" 2>/dev/null || true
    exit 1
fi
