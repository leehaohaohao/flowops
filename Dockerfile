# 基础镜像：官方 openjdk 镜像已停止维护，openjdk:17-jdk-slim 在 Docker Hub 上已无法解析（构建报 not found），
# 改用 Eclipse Temurin 17（Ubuntu 22.04 jammy，故下面的 apt 源与 docker-ce 仓库都走 ubuntu 分支）。
# 注意：DockerfileGenerator 生成的 Java 服务镜像默认基础镜像需与此保持一致。
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# 安装 Docker CLI（用于在容器内操作宿主机 Docker）
RUN sed -i -e 's|archive.ubuntu.com|mirrors.aliyun.com|g' -e 's|security.ubuntu.com|mirrors.aliyun.com|g' /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
    && chmod a+r /etc/apt/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
       > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /data/flowops/services /data/flowops/logs

# 复制 Spring Boot fat JAR（由 deploy-prod.sh 重命名为 app.jar）
COPY app.jar app.jar

EXPOSE 8080 8082
# 8080：HTTP API / 前端静态资源（默认映射到宿主机 8880）
# 8082：Nexa Protocol Master，主节点与子节点通信（需映射到宿主机并对子节点放通）

# 默认 prod，可通过 docker run -e SPRING_PROFILES_ACTIVE=dev 覆盖
ENTRYPOINT exec java -jar app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}
