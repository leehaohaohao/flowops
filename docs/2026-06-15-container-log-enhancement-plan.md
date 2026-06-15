# 容器日志查询增强实施计划

> 日期：2026-06-15

## Context

当前容器运行时日志（`docker compose logs`）只支持 `tail`（行数）和 `follow`（实时跟踪）两个参数。用户需要按时间范围查询、查看时间戳、关键词过滤等能力。`docker compose logs` 原生支持 `--since`、`--until`、`--timestamps` 参数，后端只需透传即可。前端需要增加对应的控件。

---

## Phase 1: 后端 - REST 接口扩展

### Step 1.1: DeployController 增加参数

**文件**: `flowops-app/src/main/java/com/nexa/flowops/controller/DeployController.java`

现有签名（第 90-97 行）：
```java
@GetMapping("/logs/{serviceId}")
public Result<String> containerLogs(
        @PathVariable Long serviceId,
        @RequestParam(defaultValue = "500") int tail)
```

改为：
```java
@GetMapping("/logs/{serviceId}")
public Result<String> containerLogs(
        @PathVariable Long serviceId,
        @RequestParam(defaultValue = "500") int tail,
        @RequestParam(required = false) String since,
        @RequestParam(required = false) String until,
        @RequestParam(defaultValue = "false") boolean timestamps)
```

参数说明：
- `since`：起始时间，支持相对格式（`30m`、`2h`、`1d`）和绝对格式（`2026-06-15T10:00:00`），透传给 `--since`
- `until`：截止时间，格式同 `since`，透传给 `--until`
- `timestamps`：是否在每行前显示时间戳，透传给 `--timestamps`

### Step 1.2: DeployExecutorService 适配新参数

**文件**: `flowops-app/src/main/java/com/nexa/flowops/service/DeployExecutorService.java`

现有 public 方法签名（第 410 行）：
```java
public String getContainerLogs(Long serviceId, int tail)
```

改为：
```java
public String getContainerLogs(Long serviceId, int tail, String since, String until, boolean timestamps)
```

命令构建逻辑（第 416 行）从：
```java
"docker", "compose", "logs", "--tail", String.valueOf(tail), "--no-color", service.getName()
```

改为动态拼接：
```java
List<String> cmd = new ArrayList<>(Arrays.asList(
    "docker", "compose", "logs",
    "--tail", String.valueOf(tail),
    "--no-color"
));
if (timestamps) cmd.add("--timestamps");
if (since != null && !since.isEmpty()) { cmd.add("--since"); cmd.add(since); }
if (until != null && !until.isEmpty()) { cmd.add("--until"); cmd.add(until); }
cmd.add(service.getName());
```

---

## Phase 2: 后端 - WebSocket 协议扩展

### Step 2.1: ContainerLogWebSocketHandler 增加参数解析

**文件**: `flowops-app/src/main/java/com/nexa/flowops/ws/ContainerLogWebSocketHandler.java`

现有客户端消息格式（第 107 行附近）：
```json
{"serviceId": 1, "tail": 200, "follow": true}
```

扩展为：
```json
{
  "serviceId": 1,
  "tail": 200,
  "follow": true,
  "since": "2h",
  "until": "",
  "timestamps": true,
  "grep": "ERROR"
}
```

命令构建（第 107-113 行）从：
```java
"docker", "compose", "logs",
"--tail", String.valueOf(tail),
"--no-color",
follow ? "--follow" : "--no-follow",
serviceName
```

改为与 REST 接口一致的动态拼接，额外处理 `grep` 参数（服务端过滤，不传给 docker）。

### Step 2.2: grep 过滤实现

在 `ContainerLogWebSocketHandler` 中，读取每行日志后，如果客户端指定了 `grep` 参数，则用 `line.contains(grep)` 过滤，只推送匹配的行。这样减少 WebSocket 传输量，前端不需要做大量文本过滤。

---

## 前端对接说明

> 后端 Phase 1 & 2 已完成，以下是前端集成所需的信息。

### REST 接口变更

**路径变更**：容器日志接口已从 `DeployController` 迁移到 `LogController`

| 项目 | 旧 | 新 |
|---|---|---|
| 路径 | `GET /api/deploy/logs/{serviceId}` | `GET /api/logs/container/{serviceId}` |
| 参数 | `tail` | `tail`, `since`, `until`, `timestamps` |

**参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `serviceId` | Long (path) | — | 服务 ID |
| `tail` | int | 500 | 显示最后 N 行 |
| `since` | String | null | 起始时间，相对格式 `30m`/`2h`/`1d` 或绝对格式 `2026-06-15T10:00:00` |
| `until` | String | null | 截止时间，格式同 `since` |
| `timestamps` | boolean | false | 是否在每行前显示 Docker 时间戳 |

**示例**：
```
GET /api/logs/container/7?tail=100&since=1h&timestamps=true
```

### WebSocket 协议变更

**端点不变**：`/ws/container-logs?token=xxx`

**客户端发送消息扩展**：

```json
{
  "serviceId": 1,
  "tail": 200,
  "follow": true,
  "since": "2h",
  "until": "",
  "timestamps": true,
  "grep": "ERROR"
}
```

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `serviceId` | number | — | 服务 ID（必填） |
| `tail` | number | 200 | 显示最后 N 行 |
| `follow` | boolean | true | 是否实时跟踪 |
| `since` | string | null | 起始时间 |
| `until` | string | null | 截止时间 |
| `timestamps` | boolean | false | 显示时间戳 |
| `grep` | string | null | 关键词过滤（服务端过滤，仅推送匹配行） |

**服务端推送消息不变**：
- `{"type":"status", "msg":"正在连接容器日志..."}`
- `{"type":"statusLine", "msg":"已连接 xxx 容器日志 (实时跟踪, since=2h, grep=ERROR)"}`
- `{"type":"line", "msg":"<日志行>"}`
- `{"type":"end", "msg":"日志流已结束"}`
- `{"type":"error", "msg":"..."}`

### 前端改动要点

1. **API 调用**：`logs.ts` 中 `getContainerLogs` 路径改为 `/api/logs/container/{serviceId}`，新增 `since`/`until`/`timestamps` 参数
2. **工具栏控件**：增加时间范围 Select、时间戳 Switch、关键词过滤 Input.Search
3. **WebSocket 消息**：发送时携带 `since`/`timestamps`/`grep` 字段
4. **LogViewer 增强**：行号显示 + 关键词高亮（接收 `highlight` prop）

---

## Phase 3: 前端 - ContainerLogs 页面改造

### Step 3.1: 扩展 API 层

**文件**: `flowops-front/src/api/logs.ts`

`getContainerLogs` 函数（第 32 行）从：
```typescript
getContainerLogs(serviceId: number, tail?: number)
```

改为：
```typescript
getContainerLogs(serviceId: number, params?: {
  tail?: number
  since?: string
  until?: string
  timestamps?: boolean
})
```

### Step 3.2: 增加工具栏控件

**文件**: `flowops-front/src/pages/ContainerLogs.tsx`

现有控件区域（第 130-148 行）增加：

| 控件 | 组件 | state | 说明 |
|---|---|---|---|
| 时间范围 | `Select` | `since` | 选项：不限 / 最近5分钟 / 15分钟 / 30分钟 / 1小时 / 2小时 / 6小时 / 12小时 / 24小时，对应值：`null` / `5m` / `15m` / `30m` / `1h` / `2h` / `6h` / `12h` / `24h` |
| 显示时间戳 | `Switch` | `timestamps` | 默认关闭 |
| 关键词过滤 | `Input.Search` | `grep` | 回车触发，传给 WebSocket 做服务端过滤 |

布局建议（使用 Ant Design 的 `Space` 或 `Flex`）：
```
第一行：[显示行数 Select] [时间范围 Select] [显示时间戳 Switch]  [加载日志] [实时跟踪] [停止跟踪] [清空]
第二行：[关键词过滤 Input.Search]
```

### Step 3.3: WebSocket 消息扩展

**文件**: `flowops-front/src/pages/ContainerLogs.tsx`

WebSocket 发送的初始消息（第 85 行附近）从：
```json
{"serviceId": xxx, "tail": 200, "follow": true}
```

改为：
```json
{"serviceId": xxx, "tail": 200, "follow": true, "since": "2h", "timestamps": true, "grep": "ERROR"}
```

同步更新 REST 调用，传入 `since`、`timestamps` 参数。

---

## Phase 4: 前端 - LogViewer 组件增强

### Step 4.1: 增加行号显示

**文件**: `flowops-front/src/components/LogViewer.tsx`

- 每行左侧添加行号（固定宽度，右对齐，灰色文字）
- 行号区域与日志内容用竖线分隔
- 实现方式：将 `content` 按 `\n` split 后 map 渲染，行号用 `<span>` 单独显示

### Step 4.2: 增加关键词高亮

**文件**: `flowops-front/src/components/LogViewer.tsx`

- 接收 `highlight` prop（string）
- 渲染时用正则将匹配文字包裹在 `<mark>` 标签中（黄色背景）
- 仅在有 `highlight` 值时启用，避免性能损耗

---

## 不在本次范围内

| 功能 | 原因 |
|---|---|
| DeployLogs 页面 offset/limit 分页 | 属于文件日志体系，与容器日志无关，单独规划 |
| LogViewer 虚拟滚动 | 日志量极大时的性能优化，后续单独做 |
| head 模式（从头读 N 行） | `docker compose logs` 不支持 `--head`，需要文件日志体系支持 |
| 日志级别过滤（INFO/WARN/ERROR） | 依赖应用日志格式标准化，暂不适合通用实现 |

---

## 改动文件清单

| 文件 | 改动类型 | 所属阶段 |
|---|---|---|
| `flowops-app/.../controller/DeployController.java` | 修改 | Phase 1 |
| `flowops-app/.../service/DeployExecutorService.java` | 修改 | Phase 1 |
| `flowops-app/.../ws/ContainerLogWebSocketHandler.java` | 修改 | Phase 2 |
| `flowops-front/src/api/logs.ts` | 修改 | Phase 3 |
| `flowops-front/src/pages/ContainerLogs.tsx` | 修改 | Phase 3 |
| `flowops-front/src/components/LogViewer.tsx` | 修改 | Phase 4 |

## 验证方式

1. 后端编译：`mvn compile`
2. 前端编译：`npm run build`
3. REST 接口测试：`curl "/api/deploy/logs/7?tail=100&since=1h&timestamps=true"` 验证返回带时间戳的日志
4. WebSocket 测试：连接 `/ws/container-logs`，发送 `{"serviceId":7,"tail":50,"follow":true,"since":"30m","grep":"ERROR"}`，验证只收到包含 ERROR 的行
5. 前端功能测试：在 ContainerLogs 页面切换各控件，验证日志内容正确更新
