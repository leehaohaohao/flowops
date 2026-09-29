# Docker SDK 模块

## Context

项目中 Docker 命令分散在 `DockerUtil`、`DeployExecutorService`、`LogService`、`ContainerLogWebSocketHandler` 四个类中，各自用 `ProcessBuilder` 直接调用，缺乏统一的日志、错误处理和耗时统计。需要新建独立的 `flowops-docker` 模块，作为项目自有的 Docker SDK，封装所有 Docker 操作。

## 现状分析

| 文件 | Docker 调用 | 方式 |
|------|------------|------|
| `DockerUtil.java` | `dockerPath()`, `newProcessBuilder()`, `isContainerRunning()`, `getContainerLog()` | ProcessBuilder |
| `DeployExecutorService.java` | `docker compose -f <path> down/up/stop/restart/ps/logs` | `dockerUtil.newProcessBuilder()` + 手动读取 |
| `LogService.java` | `docker compose --project-directory <dir> logs` | `dockerUtil.newProcessBuilder()` + 手动读取 |
| `ContainerLogWebSocketHandler.java` | `docker info`, `docker compose --project-directory <dir> logs --follow` | 直接 `new ProcessBuilder()` |

## 方案

### 1. 新建 `flowops-docker` 模块

Maven 模块，包名 `com.nexa.flowops.docker`，纯 Java 库 + Spring Boot 自动注册。

```
flowops-docker/
├── pom.xml
└── src/main/java/com/nexa/flowops/docker/
    ├── DockerClient.java          # 核心接口
    ├── DockerResult.java          # 执行结果 record
    └── DefaultDockerClient.java   # 实现类 (@Component)
```

**pom.xml**: 仅依赖 `spring-boot-starter`（用于 `@Component`、`@Value`、Logger），无其他第三方依赖。

### 2. `DockerResult.java`

```java
public record DockerResult(int exitCode, String output, long elapsedMs, String command) {
    public boolean isSuccess() { return exitCode == 0; }
    public String outputTail(int maxLen) { ... } // 截取末尾 N 字符用于日志
}
```

### 3. `DockerClient.java` 接口

```java
public interface DockerClient {
    /** 同步执行 docker 命令，等待完成，返回结果 */
    DockerResult execute(String... commands);
    DockerResult execute(File workDir, String... commands);

    /** 流式启动 docker 进程，返回 Process 供调用方读取流（用于 docker logs --follow） */
    Process startStreaming(String... commands);
    Process startStreaming(File workDir, String... commands);

    /** 便捷方法 */
    boolean isContainerRunning(String containerName);
    String getContainerLog(String containerName, int tail);
}
```

### 4. `DefaultDockerClient.java` 实现

- **`@Component`** 注册为 Spring Bean
- **docker 路径解析**: 优先 `/usr/bin/docker`、`/usr/local/bin/docker`，回退 `docker`
- **`execute()`**: `ProcessBuilder` + `redirectErrorStream(true)` → `readProcessOutput()` → `waitFor()` → 封装 `DockerResult`。自动打印命令、工作目录、退出码、耗时
- **`startStreaming()`**: `ProcessBuilder` + `redirectErrorStream(true)` → 直接返回 `Process`，不 waitFor
- **日志格式**:
  ```
  [Docker] 执行: docker compose -f /data/.../docker-compose.yml down (dir=/data/...)
  [Docker] 完成: exitCode=0, 耗时=1234ms
  ```

### 5. 父 pom.xml 添加模块

```xml
<modules>
    <module>flowops-common</module>
    <module>flowops-docker</module>   <!-- 新增 -->
    <module>flowops-permission</module>
    <module>flowops-app</module>
</modules>
```

### 6. flowops-app 添加依赖

```xml
<dependency>
    <groupId>com.nexa</groupId>
    <artifactId>flowops-docker</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 7. 重构调用方

**`DeployExecutorService.java`**:
- 注入 `DockerClient` 替代 `DockerUtil`
- 所有 `dockerUtil.newProcessBuilder()` + `readProcessOutput()` + `waitFor()` → `dockerClient.execute(workDir, ...)`
- `waitForContainers()` 中的 `docker compose ps` → `dockerClient.execute(workDir, ...)`
- 移除内部 `readProcessOutput()` 方法

**`LogService.java`**:
- 注入 `DockerClient` 替代 `DockerUtil`
- `getContainerLogs()` → `dockerClient.execute("docker", "compose", "--project-directory", volumeDir, "logs", ...)`
- 移除内部 `readProcessOutput()` 方法

**`ContainerLogWebSocketHandler.java`**:
- 注入 `DockerClient`
- `docker info` 测试 → `dockerClient.execute("docker", "info")`
- `docker compose logs --follow` → `dockerClient.startStreaming("docker", "compose", "--project-directory", volumeDir, "logs", ...)` + 手动读取流

**删除 `DockerUtil.java`**

## 涉及文件

| 操作 | 文件 |
|------|------|
| 新建 | `flowops-docker/pom.xml` |
| 新建 | `flowops-docker/src/main/java/com/nexa/flowops/docker/DockerClient.java` |
| 新建 | `flowops-docker/src/main/java/com/nexa/flowops/docker/DockerResult.java` |
| 新建 | `flowops-docker/src/main/java/com/nexa/flowops/docker/DefaultDockerClient.java` |
| 修改 | `pom.xml`（添加 module） |
| 修改 | `flowops-app/pom.xml`（添加依赖） |
| 修改 | `flowops-app/.../service/DeployExecutorService.java` |
| 修改 | `flowops-app/.../service/LogService.java` |
| 修改 | `flowops-app/.../ws/ContainerLogWebSocketHandler.java` |
| 删除 | `flowops-app/.../util/DockerUtil.java` |

## 验证

```bash
./mvnw compile
```
