# 远程部署完善（产物传输 + 远程状态/日志 + 前端节点支持）可行性分析

> **实施状态（2026-08-25 更新）**：
> - ✅ **步骤 1（后端）已实现后已被取代**：原 `ArtifactDownloadController`（HTTP tar 下载）已按 `2026-08-25-artifact-standardization-node-auth-plan.md` **删除**，产物传输改为协议分块（`service/artifact/ArtifactTransferManager`）+ 注册表
> - ✅ **步骤 4（后端）已实现**：`QueryManager`（按 runnerId 同步等待回执）、`FlowOpsMasterListener` 查询回执回调、`DeployExecutorService.getContainerStatus` / `LogService.getContainerLogs` 按 nodeId 路由
> - ⬜ **步骤 2 / 5（子节点 flowops-executor）**、**步骤 6（前端）** 待做；步骤 3 协议 v0.4.0 → v0.5.0 已发布
> - 依赖变更：`flowops-app` 升级 nexa-protocol 0.5.0，新增 commons-compress

## Context

分布式任务下发链路已全部实现：协议（nexa-protocol v0.3.0）定义任务消息、后端（flowops）完成本机/远程编排与回执落库、子节点（flowops-executor）实现任务接收与 docker 执行。但审核发现存在 3 个功能缺口，使远程部署**无法端到端真正可用**：

1. **产物（jar/dist 二进制）未传输** — 新服务首次远程 `START` 会在子节点构建失败
2. **远程容器状态 / 日志未打通** — 状态查询忽略 nodeId 总查本机；容器日志仅支持本机
3. **前端无节点支持** — 无法在 UI 上选择目标节点，远程部署能力不可达；且 `DeployService` 类型残留后端不返回的历史字段（`projectName`/`port`/`extraPorts`）

本计划覆盖这三项，使远程部署可用。**文档按开发顺序组织**：每步标明所属仓库与前置依赖，可分别派发给代码 agent。**本计划仅做可行性分析与任务划分，不涉及代码改动。**

## 现状分析

### 已完成（本次不再动）

| 端 | 内容 |
|----|------|
| 协议 v0.3.0 | `task.proto`（TaskRequest/Response）、`TASK_DISPATCH_REQ/RESP`、Java handler 可扩展、`onTaskResult` 回调、两端 codec |
| 后端 | `DeployExecutorService` 本机/远程路由、`RemoteDeployDispatcher` 下发、`RemoteTaskManager` 回执落库/超时/掉线、`NodeService` 负载调度、`V3_0_0` 迁移 node_id |
| 子节点 | `startTaskLoop` 接收循环、`handleTask` 配置落盘 + docker compose 执行 + 回执、路径穿越防护、真实心跳 |

### 三个缺口详情

**缺口 1：产物未传输**（`service/deploy/RemoteDeployDispatcher.java:140` `buildConfigMap`）
- 只发送 3 份配置文件 + 元数据 JSON，不含上传的二进制
- 产物布局（`service/deploy/UploadHelper.java`）：jar → `{volumeDir}/app.jar`；dist → `{volumeDir}/dist/`；与生成配置（docker-compose.yml / Dockerfile / default.conf）混在同一目录
- 影响：子节点 `docker compose up -d --build` 时 Dockerfile 引用的产物不存在 → 首次远程部署失败；STOP/RESTART/REMOVE 不受影响（无需构建）

**缺口 2：远程容器状态 / 日志未打通**
- `DeployExecutorService.getContainerStatus()`（:98-101）忽略 nodeId，总是查本机 docker
- `LogService` / `ContainerLogWebSocketHandler` 无 nodeId 分支，仅本机可用
- 影响：远程服务状态查询返回错误结果、容器日志不可见

**缺口 3：前端无节点支持 + 历史遗留字段**（`D:\project\front\flowops-front`）
- 无 `nodeId` 字段：`ServiceEdit` 表单无「目标节点」选项，save payload 不含 nodeId
- 无 `NodeInfo` 类型、无 `/api/nodes` 调用、无节点 UI
- 影响：所有服务 nodeId 为空 → 全部走本机，远程部署在 UI 不可达（后端 `auto` 自动调度也无法触发）
- **历史遗留**：`DeployService` 类型声明了后端实体不返回的 `projectName` / `port` / `extraPorts`（早期 extra_ports/port 时代残留）。`projectName`、`extraPorts` 无任何消费；**`port` 有 6 处接触点，均为死代码**（后端不返回该字段，恒为 undefined）：`ServiceEdit.tsx:236,240`（回显默认 fallback）、`:250`（隐藏表单字段）、`:325`（save payload，后端 DTO 无 `port` 被 Jackson 忽略）、`ServiceList.tsx:146,156`（死分支）。页面标题的项目名走 `getProject()` 本地 state（`ServiceList.tsx:55,67`），与服务记录无关

## 开发顺序总览

**必须先完成的两个前置**（互相独立，可并行）：**步骤 1（后端产物下载端点）** 与 **步骤 3（协议查询消息类型）**。其余步骤在此基础上按依赖推进。

```
必须最先完成（两个独立前置，可并行）
├── 步骤 1  后端   ：产物下载端点        （无前置依赖）
└── 步骤 3  协议   ：查询消息类型        （无前置依赖，契约基础）
        │
按依赖推进
├── 步骤 2  子节点 ：产物下载            ← 依赖步骤 1（需端点契约）
├── 步骤 4  后端   ：查询路由 + QueryManager ← 依赖步骤 3
├── 步骤 5  子节点 ：查询处理            ← 依赖步骤 3（可与步骤 4 并行）
└── 步骤 6  前端   ：节点支持 + 遗留清理 ← 依赖后端 API（已就绪，可随时并行）
```

| 步骤 | 内容 | 仓库 | 前置依赖 | 完成后意义 |
|------|------|------|---------|-----------|
| 1 | 产物下载端点 + `artifact_url` 填充 | 后端 | 无 | 子节点可拉取产物 |
| 2 | START 前下载产物并解压 | 子节点 | 步骤 1 | **首次远程部署端到端可用** |
| 3 | 状态/日志查询消息 + codec + handler | 协议 | 无 | 状态/日志查询的契约基础 |
| 4 | `QueryManager` + 状态/日志按 nodeId 路由 | 后端 | 步骤 3 | 远程状态/日志可查询 |
| 5 | STATUS / LOGS 查询处理 | 子节点 | 步骤 3 | 子节点能响应查询 |
| 6 | 目标节点选择 + 遗留字段清理 | 前端 | 后端 API（已就绪） | UI 可用远程部署 |

> 步骤 1 与步骤 3 可并行派活；步骤 4 与步骤 5 可并行派活。**建议优先完成步骤 1+2（产物传输）**，让远程部署先真正能跑起来。

## 开发步骤（按顺序）

### 步骤 1：后端 — 产物下载端点（最先做，无前置依赖）

**负责**：主节点新增产物下载端点，并在下发任务时填充 `artifact_url`。**不负责**：子节点下载逻辑、协议改动。

| 改动 | 文件 | 说明 |
|------|------|------|
| 新建 | `service/deploy/ArtifactDownloadController.java`（或并入 `DeployController`） | `GET /api/deploy/artifact/{serviceId}`，把该服务 volumeDir **整个目录 tar 打包**流式下发（配置 + 产物一起，最简单可靠） |
| 修改 | `service/deploy/RemoteDeployDispatcher.java` | `buildConfigMap` 后填充 `artifact_url`（拼接主节点地址 + 端点 + 鉴权）；`buildConfigMap` 生成本机文件仅作暂存可保留 |

**验收**：本机 curl `GET /api/deploy/artifact/{serviceId}` 能下载到 tar，内容含 jar/dist/配置。

### 步骤 2：子节点 — 产物下载（依赖步骤 1）

**负责**：START 动作前从主节点拉取产物。**不负责**：主节点端点。

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `runner/task.go` | `executeTask` 在 `START` 动作时：下载 `artifact_url` → 解压 tar 到 `volume_dir` → 再写 config（config 以消息为准覆盖）→ 构建 |

**验收**：首次远程 `START` 成功——产物下载、解压、构建、启动全通。

### 步骤 3：协议 — 查询消息类型（契约基础，与步骤 1 可并行）

**负责**：新增状态/日志查询消息类型，生成两端代码，Master SDK 支持同步查询回执。**不负责**：业务逻辑。

复用 Envelope 已有的 `request_id` 做请求/响应关联，主节点同步等待回执（带超时）。

**新增消息类型**（`proto/common.proto`）：
- `CONTAINER_STATUS_REQ = 8` / `CONTAINER_STATUS_RESP = 9`
- `CONTAINER_LOGS_REQ = 10` / `CONTAINER_LOGS_RESP = 11`

**新建 `proto/query.proto`**：

```proto
message ContainerStatusRequest {
  string service_id = 1;
  string deploy_name = 2;
  string volume_dir = 3;
}
message ContainerStatusResponse {
  string runner_id = 1;
  bool running = 2;
  string status = 3;     // running / stopped / restarting / exited...
  string output = 4;
  string error = 5;
}

message ContainerLogsRequest {
  string service_id = 1;
  string deploy_name = 2;
  string volume_dir = 3;
  int32 tail = 4;        // 尾部行数
  string since = 5;      // 可选
  string until = 6;      // 可选
  bool timestamps = 7;
}
message ContainerLogsResponse {
  string runner_id = 1;
  string content = 2;
  string error = 3;
}
```

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `proto/common.proto` | `MessageType` 加 `CONTAINER_STATUS_REQ=8/RESP=9`、`CONTAINER_LOGS_REQ=10/RESP=11` |
| 新建 | `proto/query.proto` | 按上文契约定义四个消息 |
| 生成 | Java + Go | 重新生成 protobuf 代码 |
| 修改 | Java `master/NexaMaster.java` | 注册 `ContainerStatusHandler` / `ContainerLogsHandler` |
| 修改 | Java `master/NexaMasterListener.java` | 增加 `onContainerStatus` / `onContainerLogs` 默认回调 |
| 修改 | Java codec | `buildContainerStatusRequest/Response`、`buildContainerLogsRequest/Response`；**响应构造需支持回填 request_id** |
| 修改 | Go codec | 对应 `BuildContainerStatusRequest/Response`、`BuildContainerLogsRequest/Response` |
| 修改 | 版本号 | Java / Go Minor → 0.4.0 |

**验收**：`mvn install` / `go build` 通过；master 下发 STATUS 请求能收到响应并关联到 request_id；Go client 能解出查询消息。

### 步骤 4：后端 — 查询路由 + QueryManager（依赖步骤 3）

**负责**：同步查询封装、状态/日志按 nodeId 路由。**不负责**：协议定义、子节点执行。

| 改动 | 文件 | 说明 |
|------|------|------|
| 新建 | `service/node/QueryManager.java` | `CompletableFuture` 以 `request_id` 关联，`sendAndAwait(runnerId, envelope, timeout)` 同步等待 |
| 修改 | `config/master/FlowOpsMasterListener.java` | 实现 `onContainerStatus` / `onContainerLogs` → 交 QueryManager 完成 future |
| 修改 | `service/deploy/DeployExecutorService.java` | `getContainerStatus` 按 nodeId 路由：本机走 `LocalDeployRunner`，远程走 `QueryManager` |
| 修改 | `service/LogService.java` | `getContainerLogs` 按 nodeId 路由（非流式 tail） |

> 流式日志（`/ws/container-logs` 的 `--follow`）对远程节点本期不做，先非流式 tail；后续可选。

**验收**：远程服务状态查询返回正确 running/stopped；远程服务日志 tail 返回内容。

### 步骤 5：子节点 — 查询处理（依赖步骤 3，可与步骤 4 并行）

**负责**：响应状态/日志查询。**不负责**：主节点编排。

| 改动 | 文件 | 说明 |
|------|------|------|
| 新建 | `runner/query.go` | 处理 `CONTAINER_STATUS_REQ`（`docker compose ps` 解析状态）、`CONTAINER_LOGS_REQ`（`docker compose logs --tail N`） |
| 修改 | `runner/runner.go` | `startTaskLoop` switch 增加两个 case；**响应 Envelope 复用请求的 request_id**，供主节点关联 |

**验收**：收到 STATUS / LOGS 查询能正确回传。

### 步骤 6：前端 — 节点支持 + 遗留清理（依赖后端 API 已就绪，可随时并行）

**负责**：目标节点选择、节点信息展示、历史遗留字段清理。**不负责**：后端逻辑。

#### A. 节点支持

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `src/types/index.ts` | `DeployService` 加 `nodeId?: string`；新增 `NodeInfo` 接口（runnerId/hostname/ip/version/lastHeartbeatTime/online/runningTasks/cpuUsage/memoryUsage） |
| 新建 | `src/api/nodes.ts` | `getNodeList()` → `GET /api/nodes`、`getNode(runnerId)` |
| 修改 | `src/pages/ServiceEdit.tsx` | 基础信息区加「目标节点」下拉（本机空值 / 自动调度 `auto` / 在线节点）；save payload 加 `nodeId`；编辑回显 |
| 修改 | `src/pages/ServiceList.tsx` | 加「节点」列（本机 / auto / runnerId），可选 |
| 新建（可选） | `src/pages/NodeList.tsx` + 路由 + 菜单 | 节点在线状态、负载、最后心跳展示 |

#### B. 历史遗留字段清理

后端 `DeployService` 实体不含 `projectName` / `port` / `extraPorts`，前端类型仍声明。**`port` 是重点：共 6 处接触点**（非仅 ServiceList 两处），删除类型字段会触发 `tsc -b` 报错，需连代码一起清。

`port` 全部接触点（均为死代码，后端不返回该字段，运行时恒为 undefined）：

| # | 位置 | 现状 | 处理 |
|---|------|------|------|
| 1 | `ServiceEdit.tsx:236` | fullstack 回显 `hostPort: svc.port \|\| 80` | 改 `hostPort: 80` |
| 2 | `ServiceEdit.tsx:240` | backend 回显 `hostPort: svc.port \|\| 8080` | 改 `hostPort: 8080` |
| 3 | `ServiceEdit.tsx:250` | 隐藏表单字段 `port: primaryPort?.hostPort \|\| svc.port` | 删该行；连带删 `primaryPort`（:243） |
| 4 | `ServiceEdit.tsx:325` | save payload `port: primaryMapping?.hostPort \|\| 0`（后端 DTO 无 `port`，被忽略） | 删该字段；连带删 `primaryMapping`（:320） |
| 5 | `ServiceList.tsx:146` | 死分支 `record.port` | 删除 |
| 6 | `ServiceList.tsx:156` | 死分支 `record.port` | 删除 |

> 级联：删 #3 后 `primaryPort`（:243）变未使用；删 #4 后 `primaryMapping`（:320）变未使用，需一并删除。`projectName` / `extraPorts` 无任何消费，直接删类型字段即可。

其余改动：

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `src/types/index.ts` | 删除 `DeployService.projectName`（:103）、`extraPorts`（:109）、`port`（:104） |
| 确认 | `src/pages/ServiceList.tsx` | 页面标题 projectName 来自 `getProject()` 本地 state（:55,67），与服务记录无关，清理后不受影响 |

**注意**：删 `port` 前必须同步清理上表 6 处，否则 `tsc -b` 在 ServiceEdit / ServiceList 报 "Property 'port' does not exist"。

**验收**：
- 创建/编辑服务可选目标节点；部署后列表能显示执行节点；可选节点页能看在线状态与负载
- 清理后 `npm run build`（含 `tsc -b`）无类型错误、无未使用字段/变量；服务列表与编辑回显渲染正常、项目名标题仍显示

## 可行性分析

| 维度 | 现状 | 结论 |
|------|------|------|
| 产物传输 | jar/dist 已在 master volumeDir；HTTP 下载端点新增即可；runner 已有 exec 能力 | ✅ 可行，工作量小 |
| 远程状态/日志 | Envelope.request_id 已支持关联；Java handler 链已可扩展；runner 已有 ReadEnvelope 循环 | ✅ 可行，需协议 v0.4.0 |
| 前端 | 后端 API 已就绪（/api/nodes、services.nodeId），纯前端新增 | ✅ 可行 |

### 风险与关键问题

| # | 风险点 | 影响 | 应对 |
|---|--------|------|------|
| 1 | **产物下载端点鉴权**：暴露任意服务 volumeDir 下载有信息泄露风险 | 高 | 加鉴权：共享密钥（子节点配置）或主节点登录 token（见待决策 1） |
| 2 | **子节点需能访问主节点 HTTP**：当前只通 TCP 9090，需主节点 8080 可达（NAT/防火墙/跨机器） | 高 | 部署拓扑上确保 8080 可达；artifact_url 可配置主节点地址 |
| 3 | **流式日志（--follow）远程不支持**：请求/响应模型不适合长连接流 | 中 | 本期做非流式 tail；远程流式日志（WS 代理）后置为可选阶段 |
| 4 | **大产物 tar 传输**：内存/磁盘占用，master 需流式打包不整载内存 | 中 | 用 tar 输出流直接写响应体，避免全量读入内存 |
| 5 | **配置与产物混放**：tar 整目录含配置，需明确覆盖顺序 | 低 | 顺序固定：先解压 tar，再写 config 消息（config 为准） |
| 6 | **同步查询超时/节点掉线**：QueryManager 需超时兜底 | 中 | `sendAndAwait` 带超时；超时返回失败状态，不阻塞前端 |
| 7 | **查询响应 request_id 关联**：新 codec builder 需回填 request_id，否则主节点无法关联 | 中 | 步骤 3 明确要求响应构造复用请求 request_id |

### 结论

**方案可行**。三缺口均为增量实现，无架构性阻碍：
- 产物传输：主节点一个下载端点 + runner 一段下载解压逻辑
- 远程状态/日志：协议加 4 个消息类型 + master 同步查询封装 + runner 两个 case
- 前端：表单加字段 + 可选节点页

主要风险集中在**产物端点的鉴权**与**子节点到主节点的 HTTP 可达性**，均可在部署拓扑层解决。

## 关键决策记录

| 决策 | 结论 | 原因 |
|------|------|------|
| 产物传输方式 | 子节点 HTTP 拉取整个 volumeDir tar | 简单可靠，复用现有存储布局，无需改上传流程 |
| 远程状态/日志 | 新增同步查询消息对，复用 request_id 关联 | 状态/日志需要同步语义，前端无需改轮询 |
| 远程日志范围 | 本期仅非流式 tail | 请求/响应模型不适合 --follow 长连接，流式后置 |
| 前端范围 | nodeId 选择必做；节点列表页可选 | nodeId 缺失则远程部署 UI 不可达 |
| 前端遗留字段 | `projectName` / `port` / `extraPorts` 删除 | 后端不返回且前端不消费，留着误导后来人；页面项目名走 `getProject()` 不受影响 |

## 里程碑（与开发步骤对应）

| 阶段 | 对应步骤 | 内容 | 完成标志 |
|------|---------|------|---------|
| Phase 1（产物传输） | 步骤 1 + 2 | 首次远程 START 端到端可用 | 远程部署建容器成功，deploy_record=success |
| Phase 2（状态/日志） | 步骤 3 + 4 + 5 | 远程状态/日志可查询 | `/api/deploy/status` 返回 running；`/api/logs/container` 有内容 |
| Phase 3（前端） | 步骤 6 | UI 可选择目标节点 + 遗留清理 | `npm run build` 通过；部署可选节点 |
| 可选（流式日志） | — | 远程 `--follow`（WS 代理） | 远程容器日志实时滚动 |

## 验证方式（按步骤）

```bash
# 步骤 1（后端产物端点）
cd D:\project\backend\flowops && ./mvnw compile
# curl -X GET <master>/api/deploy/artifact/{serviceId} → 返回 tar，含 jar/dist/配置

# 步骤 2（子节点产物下载）
cd D:\project\go\flowops-executor && go build -o flowops-executor.exe .
# 起 master(8080/9090) + runner → 建服务(目标节点) → 上传 jar → 远程部署
# 预期：runner 下载产物 → 构建 → 启动；deploy_record=success，节点=runnerId

# 步骤 3（协议）
cd D:\project\mix\nexa-protocol\java && mvn install
cd D:\project\mix\nexa-protocol\go && go build ./...

# 步骤 4 + 5（状态/日志联调）
# 远程服务状态 /api/deploy/status/{id} 返回 running
# 远程服务日志 /api/logs/container/{id} 返回容器日志内容

# 步骤 6（前端）
cd D:\project\front\flowops-front && npm run build   # 含 tsc -b，无类型错误
```

## 待决策问题

1. 产物下载鉴权方式：共享密钥（子节点配置 + 主节点校验）/ 主节点登录 token / 内网信任免鉴权？
2. 远程流式日志（`/ws/container-logs` 的 --follow）是否本期做，还是明确后置？
3. 前端节点列表页是否本期做（还是只做 ServiceEdit 目标节点下拉 + 服务列表节点列）？
4. 产物拉取失败的重试策略：失败即回执失败 / 重试 N 次 / 由主节点侧重试？
