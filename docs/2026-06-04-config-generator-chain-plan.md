# 责任链模式重构配置文件生成

## Context

`DeployExecutorService.deploy()` 方法中包含了大量配置文件生成逻辑（Dockerfile、nginx.conf、docker-compose.yml），共 7 个 private 方法 + 11 个工具方法，约 300 行代码。这些逻辑与部署编排（docker compose down/up/stop/restart）混在一起，职责不清。现有的 `service/generate/` 目录下有 4 个脚手架文件，但都是未完成的 stub，需要整体替换。

## 方案

采用 Spring 自动发现的责任链模式，将配置生成从 `DeployExecutorService` 中完全抽离。

### 文件结构

```
com.nexa.flowops.service.generate/
  ├── ConfigGenerator.java         - 接口：supports() + generate()
  ├── DeployContext.java           - 上下文：持有所有解析后的配置数据
  ├── ConfigGeneratorChain.java    - 链：Spring 自动注入 List<ConfigGenerator>，按 @Order 执行
  ├── DockerfileGenerator.java     - @Order(1)，生成 Dockerfile
  ├── NginxConfGenerator.java      - @Order(2)，生成 default.conf
  ├── ComposeYmlGenerator.java     - @Order(3)，生成 docker-compose.yml
  └── YamlHelper.java             - 纯静态工具类，提取自 DeployExecutorService
```

删除现有 4 个 stub 文件：`ServiceConfigGenerate.java`、`ServiceConfigGenerateContext.java`、`ServiceConfig.java`、`DockerfileGenerate.java`

### 依赖关系

```
DeployExecutorService
  └── ConfigGeneratorChain
        └── List<ConfigGenerator> (Spring 自动注入，@Order 控制顺序)
              ├── DockerfileGenerator  → DeployContext, YamlHelper
              ├── NginxConfGenerator   → DeployContext, YamlHelper
              └── ComposeYmlGenerator  → DeployContext, YamlHelper
```

## 实施步骤

### Step 1: 创建 YamlHelper.java

**新文件**: `flowops-app/src/main/java/com/nexa/flowops/service/generate/YamlHelper.java`

纯静态工具类，从 `DeployExecutorService` 提取：

| 方法 | 来源行号 | 用途 |
|---|---|---|
| `getString(Map, String, String)` | L864-867 | 安全读取 Map 字符串值 |
| `getInt(Map, String, int)` | L870-875 | 安全读取 Map 整数值 |
| `splitCommand(String)` | L881-920 | Shell 命令拆分，支持引号 |
| `appendEnvironment(StringBuilder, Map)` | L615-623 | 生成 YAML environment 块 |
| `appendVolumes(StringBuilder, Map)` | L626-635 | 生成 YAML volumes 块 |
| `getPrimaryMapping(List<PortMapping>)` | L580-584 | 获取 primary 端口映射 |
| `getMappingsByTarget(List<PortMapping>, String)` | L587-595 | 按 target 过滤端口映射 |
| `getExposeMappings(List<PortMapping>)` | L598-603 | 获取 expose 端口映射 |
| `getHostPortMappings(List<PortMapping>)` | L606-611 | 获取 host 端口映射 |

### Step 2: 创建 ConfigGenerator.java 接口

**新文件**: `flowops-app/src/main/java/com/nexa/flowops/service/generate/ConfigGenerator.java`

```java
public interface ConfigGenerator {
    boolean supports(DeployContext context);
    void generate(DeployContext context) throws IOException;
}
```

### Step 3: 创建 DeployContext.java

**新文件**: `flowops-app/src/main/java/com/nexa/flowops/service/generate/DeployContext.java`

`@Data` 类，包含所有生成器需要的数据：

```java
private String volumeDir;
private String serviceType;
private DeployService service;
private Map<String, Object> backendConfig;
private Map<String, Object> frontendConfig;
private List<PortMapping> portMappings;
// nginx 专属（前端/全栈时有值）
private String proxyTarget;
private List<Map<String, Object>> proxyRules;
private String customNginx;
private int nginxListenPort;
```

静态工厂方法 `DeployContext.from(DeployService, ObjectMapper)` 负责：
- 解析 `serviceConfig` JSON → `backendConfig` / `frontendConfig`
- 解析 `portMappings` JSON → `List<PortMapping>`
- 计算 `proxyTarget`（当前 deploy() L168-191 的逻辑移入）
- 提取 `proxyRules`、`customNginx`、`nginxListenPort`

### Step 4: 创建 DockerfileGenerator.java

**新文件**: `flowops-app/src/main/java/com/nexa/flowops/service/generate/DockerfileGenerator.java`

- `@Component @Order(1)`
- `supports()`: serviceType 为 "backend" 或 "fullstack"
- `generate()`: 当前 `generateDockerfile()` 的逻辑（L272-341），写入 `{volumeDir}/Dockerfile`

### Step 5: 创建 NginxConfGenerator.java

**新文件**: `flowops-app/src/main/java/com/nexa/flowops/service/generate/NginxConfGenerator.java`

- `@Component @Order(2)`
- `supports()`: serviceType 为 "frontend" 或 "fullstack"
- `generate()`: 当前 `generateNginxConf()` 的逻辑（L343-404），从 context 读取 proxyTarget/proxyRules/customNginx/nginxListenPort，写入 `{volumeDir}/default.conf`

### Step 6: 创建 ComposeYmlGenerator.java

**新文件**: `flowops-app/src/main/java/com/nexa/flowops/service/generate/ComposeYmlGenerator.java`

- `@Component @Order(3)`
- `supports()`: 始终返回 true
- `generate()`:
  - portMappings 非空 → 走新的映射逻辑（原 L417-478）
  - portMappings 为空 → 走 legacy 兜底逻辑（原 L429-474），保证旧服务兼容
- 包含原 `generateBackendComposeFromMappings`、`generateFrontendComposeFromMappings`、`generateFullstackBackendComposeFromMappings`、`generateFullstackFrontendComposeFromMappings` 作为 private 方法
- 写入 `{volumeDir}/docker-compose.yml`

### Step 7: 创建 ConfigGeneratorChain.java

**新文件**: `flowops-app/src/main/java/com/nexa/flowops/service/generate/ConfigGeneratorChain.java`

```java
@Component
public class ConfigGeneratorChain {
    private final List<ConfigGenerator> generators;

    public ConfigGeneratorChain(List<ConfigGenerator> generators) {
        this.generators = generators;
    }

    public void generate(DeployContext context) throws IOException {
        for (ConfigGenerator generator : generators) {
            if (generator.supports(context)) {
                generator.generate(context);
            }
        }
    }
}
```

### Step 8: 修改 DeployExecutorService.java

**构造函数**增加 `ConfigGeneratorChain` 依赖

**deploy() 方法** L150-193 替换为：
```java
DeployContext context = DeployContext.from(service, objectMapper);
configGeneratorChain.generate(context);
```

**删除的方法**（共 7 个生成方法 + 11 个工具/解析方法）：
- 生成方法：`generateDockerfile`、`generateNginxConf`、`generateComposeYml`、`generateBackendComposeFromMappings`、`generateFrontendComposeFromMappings`、`generateFullstackBackendComposeFromMappings`、`generateFullstackFrontendComposeFromMappings`
- 工具方法：`getString`、`getInt`、`splitCommand`、`appendEnvironment`、`appendVolumes`、`getPrimaryMapping`、`getMappingsByTarget`、`getExposeMappings`、`getHostPortMappings`
- 解析方法：`parseServiceConfig`、`parsePortMappings`

**保留的方法**：`deploy()`（精简后）、`stopContainer`、`restartContainer`、`removeContainer`、`getContainerStatus`、`waitForContainers`、`getContainerLogs`、`readProcessOutput`、`getUploadPath`、`extractDist`、`deleteDirectory`

### Step 9: 删除旧 stub 文件

- `ServiceConfigGenerate.java`
- `ServiceConfigGenerateContext.java`
- `ServiceConfig.java`
- `DockerfileGenerate.java`

## 验证

1. `./mvnw compile` 编译通过
2. 部署一个 backend 类型服务 → 验证 Dockerfile、docker-compose.yml 内容与重构前一致
3. 部署一个 fullstack 类型服务 → 验证 Dockerfile、default.conf、docker-compose.yml 内容与重构前一致
4. 部署一个无 portMappings 的旧服务 → 验证 legacy fallback 正常工作
