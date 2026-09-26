package com.nexa.flowops.docker;

import java.io.File;

public interface DockerClient {

    // ==================== Docker 基础 ====================

    /** 检查 Docker 守护进程是否可用 */
    boolean isDockerAvailable();

    /** 检查容器是否正在运行 */
    boolean isContainerRunning(String containerName);

    /** 获取单个容器的日志 */
    String getContainerLog(String containerName, int tail);

    // ==================== Docker Compose 生命周期 ====================

    /** docker compose -f <composeFile> down */
    DockerResult composeDown(File workDir, String composeFile);

    /** docker compose -f <composeFile> up -d [--build] */
    DockerResult composeUp(File workDir, String composeFile, boolean build);

    /** docker compose -f <composeFile> stop */
    DockerResult composeStop(File workDir, String composeFile);

    /** docker compose -f <composeFile> restart */
    DockerResult composeRestart(File workDir, String composeFile);

    /** docker compose ps --format json（返回容器状态 JSON 行） */
    DockerResult composePs(File workDir);

    // ==================== Docker Compose 日志 ====================

    /** 同步获取 compose 服务日志 */
    DockerResult composeLogs(File workDir, LogsOptions options);

    /** 流式启动 compose logs --follow，返回 Process 供调用方逐行读取 */
    Process composeLogsFollow(File workDir, LogsOptions options);

    // ==================== Docker 网络（仅主节点本机操作） ====================

    /** docker network inspect --format {{json .}} <name>；网络不存在时 isSuccess() 为 false */
    DockerResult inspectNetwork(String name);

    /** docker network create --driver bridge <name>；只创建默认地址分配的用户自定义 bridge */
    DockerResult createNetwork(String name);

    /** docker network rm <name> */
    DockerResult removeNetwork(String name);

    /** docker network ls --format {{json .}}；每行一个 JSON，调用方负责过滤 driver 与内置网络 */
    DockerResult listNetworks();

    // ==================== 日志选项 ====================

    record LogsOptions(int tail, String since, String until, boolean timestamps, String service) {

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int tail = 100;
            private String since;
            private String until;
            private boolean timestamps;
            private String service;

            public Builder tail(int tail) { this.tail = tail; return this; }
            public Builder since(String since) { this.since = since; return this; }
            public Builder until(String until) { this.until = until; return this; }
            public Builder timestamps(boolean timestamps) { this.timestamps = timestamps; return this; }
            public Builder service(String service) { this.service = service; return this; }

            public LogsOptions build() {
                return new LogsOptions(tail, since, until, timestamps, service);
            }
        }
    }
}
