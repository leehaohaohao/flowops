# 端口映射重构设计：统一 port_mappings JSON 字段

## Context

当前端口数据分散在三个地方：`port` (INT 列)、`extra_ports` (VARCHAR JSON 列)、`serviceConfig` (TEXT JSON 中的 containerPort/frontendPort)。这种设计语义重复、扩展性差，且不支持多端口 EXPOSE 和内部网络端口。重构为单一 `port_mappings` TEXT JSON 列，用统一的 `PortMapping` 实体接收，覆盖所有端口场景。

### 典型需求场景

一个 Java 应用同时暴露 HTTP(10001) 和 WebSocket(10002) 两个端口，还有一个内部缓存端口(6379) 不需要暴露到宿主机：

```yaml
# 期望的 docker-compose 输出
backend:
  expose:
    - "6379"         # 仅内部网络可达
  ports:
    - "10001:10001"  # 宿主机可访问
    - "10002:10002"  # 宿主机可访问
```

---

## 端口层次架构

| 层次 | 用途 | 数据来源 | 影响的生成文件 |
|------|------|----------|----------------|
| **Docker EXPOSE** | 声明容器监听端口（文档性质） | portMappings 所有 containerPort | Dockerfile |
| **compose ports** | 宿主机↔容器端口映射（实际网络） | portMappings 中 `expose=false` 的条目 | docker-compose.yml |
| **compose expose** | Docker 内部网络可达，不暴露宿主机 | portMappings 中 `expose=true` 的条目 | docker-compose.yml |
| **nginx listen** | nginx.conf 监听端口 | `serviceConfig.frontend.nginxListenPort` | default.conf |
| **nginx proxy_pass** | 反向代理目标 | portMappings 中 backend expose 端口 | default.conf |

> portMappings 是所有端口映射的**唯一数据源**。`serviceConfig` 不再存储任何端口映射信息。

---

## PortMapping 实体定义

**新建**: `entity/PortMapping.java`

```java
@Data
public class PortMapping {
    private Integer hostPort;       // 宿主机端口（expose=true 时可为 null）
    private Integer containerPort;  // 容器端口（必填）
    private String protocol;        // "tcp" (默认) / "udp"
    private String label;           // UI 显示标签
    private boolean primary;        // 主端口映射（每种类型有且仅有一个）
    private boolean expose;         // true = 仅内部网络可达 (compose expose)，false = 映射到宿主机 (compose ports)
    private String target;          // 仅 fullstack: "backend" / "frontend"，默认 "backend"
}
```

数据库中 `port_mappings` 列存储 `List<PortMapping>` 的 JSON 序列化。

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `hostPort` | Integer | expose=false 时必填 | 宿主机端口 |
| `containerPort` | Integer | yes | 容器端口 |
| `protocol` | String | no | "tcp"(默认) / "udp" |
| `label` | String | no | UI 显示标签，如 "HTTP"、"WebSocket"、"内部API" |
| `primary` | boolean | no | 主端口映射（每种类型有且仅有一个） |
| `expose` | boolean | no | true=仅内部网络, false/null=映射到宿主机 |
| `target` | String | no | 仅 fullstack: "backend" / "frontend"，默认 "backend" |

### 各服务类型的 portMappings 示例

**backend 模式** — 多端口暴露 + 内部端口：
```json
[
  { "hostPort": 10001, "containerPort": 10001, "primary": true, "label": "HTTP" },
  { "hostPort": 10002, "containerPort": 10002, "label": "WebSocket" },
  { "containerPort": 6379, "expose": true, "label": "Redis内部" }
]
```
→ Dockerfile: `EXPOSE 10001 10002 6379`（所有 containerPort 都 EXPOSE）
→ compose:
```yaml
backend:
  build: .
  expose:
    - "6379"         # expose=true
  ports:
    - "10001:10001"  # primary
    - "10002:10002"  # extra
  restart: unless-stopped
```

**frontend 模式** — 纯前端容器：
```json
[
  { "hostPort": 80, "containerPort": 80, "primary": true, "label": "Web端口" }
]
```
→ compose:
```yaml
frontend:
  image: nginx:alpine
  ports:
    - "80:80"
```

**fullstack 模式** — 两个容器，通过 `target` 区分：
```json
[
  { "hostPort": 8080, "containerPort": 80, "primary": true, "target": "frontend", "label": "Web端口" },
  { "containerPort": 8090, "expose": true, "target": "backend", "label": "内部API" },
  { "hostPort": 9090, "containerPort": 9090, "target": "backend", "label": "API调试端口" }
]
```
→ Dockerfile: `EXPOSE 8090 9090`（backend target 的所有 containerPort）
→ compose:
```yaml
backend:
  build: .
  expose:
    - "8090"         # expose=true, 仅内部可达
  ports:
    - "9090:9090"    # expose=false, 宿主机可访问
  restart: unless-stopped
frontend:
  image: nginx:alpine
  ports:
    - "8080:80"      # primary, target=frontend
  depends_on:
    - backend
  restart: unless-stopped
```

### 生成规则总结

1. **primary**: 每种服务类型有且仅一个 `primary: true`
2. **target**: backend/frontend 模式不需要（只有一种容器）；fullstack 通过 target 区分
3. **expose 区分**:
   - `expose=true` → compose `expose:` 列表（仅内部网络）
   - `expose=false/null` → compose `ports:` 列表（宿主机可访问）
4. **Dockerfile EXPOSE**: 收集目标容器所有 `containerPort` 去重
5. **nginx proxy_pass**: fullstack 中从 portMappings 的 backend expose 端口推导（如 `http://backend:8090`）

---

## serviceConfig 变更

**保留**（非端口映射相关）：
- `backend.startupCommand` / `baseImage` / `envVars` / `dataMount`
- `frontend.nginxListenPort` → nginx.conf listen（nginx 配置，非端口映射）
- `frontend.baseImage` / `proxyRules` / `customNginxConfig`

**废弃**（全部由 portMappings 接管）：
- `backend.containerPort` → 被 portMappings 中 expose=true 的 containerPort 替代
- `frontend.containerPort` → 被 portMappings 中 primary 映射的 containerPort 替代
- `frontend.frontendPort` → 被 portMappings 中 frontend primary 的 hostPort 替代

> 迁移期间 `serviceConfig` 中这些字段保留不删，代码优先读 portMappings，为空时 fallback 到旧字段。

---

## 修改文件清单

### 1. 数据库
**新建**: `src/main/resources/sql/migration/V2_1_0__add_port_mappings.sql`
```sql
ALTER TABLE deploy_service ADD COLUMN port_mappings TEXT DEFAULT NULL COMMENT '端口映射 JSON: List<PortMapping>';
```

### 2. Entity
**新建**: `entity/PortMapping.java` — 端口映射实体类（见上方定义）

**修改**: `entity/DeployService.java`
- 新增 `private String portMappings;`

### 3. DTO
**修改**: `dto/CreateServiceRequest.java` + `dto/UpdateServiceRequest.java`
- 新增 `private String portMappings;`
- 旧字段 `port` / `extraPorts` 暂保留（迁移兼容）

### 4. Service 层
**修改**: `service/ServiceMgmtService.java`
- `createService()` / `updateService()`: 新增对 `portMappings` 字段的读写

### 5. DeployExecutorService — 核心变更
**修改**: `service/DeployExecutorService.java`

新增工具方法：
```java
// 解析 portMappings JSON → List<PortMapping>
private List<PortMapping> parsePortMappings(String json)

// 获取 primary 映射
private PortMapping getPrimaryMapping(List<PortMapping> mappings)

// 按 target 过滤
private List<PortMapping> getMappingsByTarget(List<PortMapping> mappings, String target)

// 分离 expose 和 ports 映射
private List<PortMapping> getExposeMappings(List<PortMapping> mappings)
private List<PortMapping> getHostPortMappings(List<PortMapping> mappings)
```

修改 `generateDockerfile()`：
```java
// EXPOSE: 收集目标容器所有 containerPort
Set<Integer> exposePorts = new LinkedHashSet<>();
for (PortMapping pm : portMappings) {
    if (isTargetContainer(pm, serviceType)) {
        exposePorts.add(pm.getContainerPort());
    }
}
sb.append("EXPOSE ").append(
    exposePorts.stream().map(String::valueOf).collect(Collectors.joining(" "))
).append("\n");
```

修改 `generateComposeYml()`：
- 优先从 `service.getPortMappings()` 解析
- **backend/frontend**: 区分 expose 和 hostPort 映射分别生成
- **fullstack**: 按 target 分组到 backend/frontend 容器，各自区分 expose 和 ports

保留旧 `parseExtraPorts()` + fallback 逻辑（portMappings 为空时回退到旧字段）

### 6. 迁移接口
**新建**: `controller/MigrationController.java`
- `POST /api/migration/port-mappings`

**新建**: `service/MigrationService.java`
- 迁移逻辑：
  1. 读取所有 deploy_service，跳过已有 portMappings 的
  2. 按 serviceType 构建 `List<PortMapping>`：
     - **backend**: `[{hostPort: port, containerPort: serviceConfig.backend.containerPort||8080, primary:true}] + parse(extraPorts)`
     - **frontend**: `[{hostPort: port, containerPort: serviceConfig.frontend.containerPort||80, primary:true}] + parse(extraPorts)`
     - **fullstack**:
       - `[{hostPort: frontendPort||port, containerPort: serviceConfig.frontend.containerPort||80, primary:true, target:"frontend"}]`
       - `[{containerPort: serviceConfig.backend.containerPort||8080, expose:true, target:"backend", label:"内部API"}]`
       - `parse(extraPorts)` 每项加 `target:"backend"`
  3. 序列化为 JSON 写回 portMappings 字段

### 7. 前端 React
**修改**: `front/flowops-front/src/pages/ServiceEdit.tsx`
- 表单合并为 `Form.List` 名为 `portMappings`
- 每行字段：hostPort、containerPort、label、expose(Switch)、target(fullstack时显示)
- 第一条默认 primary
- `handleSave()` 序列化 `List<PortMapping>` JSON
- `generatePreview()` 从 portMappings 读取，区分 expose/ports

**修改**: `front/flowops-front/src/pages/ServiceList.tsx`
- 合并 "端口" + "额外端口" 为 "端口映射" 列
- primary 用蓝色 Tag，expose 端口用灰色 Tag 标注"内部"

**修改**: `front/flowops-front/src/types/index.ts`
- `DeployService`: 新增 `portMappings?: string`
- 新增 `PortMapping` 接口

---

## 实施顺序

1. 新建 `entity/PortMapping.java`
2. SQL migration (加 port_mappings 列)
3. `DeployService` entity 加字段 + DTO 加字段
4. `ServiceMgmtService` 读写新字段
5. `MigrationService` + `MigrationController` (迁移接口)
6. `DeployExecutorService` (Dockerfile + compose 生成逻辑)
7. 前端 ServiceEdit.tsx + ServiceList.tsx + types
8. 编译验证 + 手动测试

---

## 验证方案

1. `./mvnw compile` 编译通过
2. `POST /api/migration/port-mappings` 迁移现有数据，确认返回成功
3. `GET /api/services/list` 确认 portMappings 字段正确
4. **多端口 backend 测试**：portMappings = [{10001:10001, primary}, {10002:10002}]
   - Dockerfile: `EXPOSE 10001 10002`
   - compose: `ports: ["10001:10001", "10002:10002"]`
5. **expose 端口测试**：portMappings 含 expose=true 条目
   - compose: `expose: ["6379"]`，不出现在 ports 中
6. **fullstack 测试**：前端主端口 + 后端 expose 内部端口 + 后端额外宿主端口
7. 前端编辑页面验证表单、预览、保存
