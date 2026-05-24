FROM openjdk:17-jdk-slim
WORKDIR /app

# 安装 Docker CLI（用于在容器内操作宿主机 Docker）
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
    && chmod a+r /etc/apt/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
       > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /data/flowops/services /data/flowops/logs

# 复制 Spring Boot fat JAR（由 deploy-prod.sh 重命名为 app.jar）
COPY app.jar app.jar

EXPOSE 8080

# 默认 prod，可通过 docker run -e SPRING_PROFILES_ACTIVE=dev 覆盖
ENTRYPOINT exec java -jar app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}
