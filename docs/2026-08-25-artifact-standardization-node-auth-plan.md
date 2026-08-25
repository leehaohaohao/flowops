# 产物标准化管理与子节点认证（注册表 + 自定义协议传输 + L1/L2 认证）可行性分析

## Context

远程部署链路已打通：任务下发、配置随消息传输、子节点执行 docker 并回执。但产物传输当前基于 **HTTP**（`ArtifactDownloadController` 流式 tar + 共享密钥），且子节点**无任何认证**（`onRegister` 无脑接受注册）。产品方向要求三点改造：

1. **产物标准化管理**：主节点集中存文件、**DB 存元数据**、子节点请求时**按 DB 匹配定位**产物
2. **传输走自有自定义协议**（nexa-protocol），不再基于 HTTP
3. **子节点全量认证**：所有操作（注册/心跳/回执/产物请求/查询）都需要身份验证

本计划替换 `2026-08-11-remote-deploy-enhancement-plan.md` 中的 **步骤 1（HTTP 产物端点）与步骤 2（子节点 HTTP 下载）**，改为「产物注册表 + 协议分块传输」；并新增子节点认证。该计划中的 **步骤 4（QueryManager）、步骤 5（查询处理）、步骤 6（前端）保持不变**，不在本文重复。

**范围外**：断线重连 / 消息重发机制**不在本计划内**，后续单独在 nexa-protocol 项目中支持。

## 现状分析

| 项 | 现状 | 本次改动 |
|----|------|---------|
| 产物存储 | 上传的 jar/dist 直接落在 `{volumeDir}`（jar→`app.jar`、binary→`app`、其他→原文件名、dist→`dist/` 解压目录），**无元数据** | 存储位置**保持不变**，仅新增 `deploy_artifact` 注册表记录元数据指向现位置 |
| 产物传输 | `ArtifactDownloadController` HTTP 流式 tar + `X-Artifact-Token` 共享密钥（`RemoteDeployDispatcher.buildArtifactUrl` 填 URL） | **删除 HTTP 端点**，改为协议分块传输 |
| 子节点认证 | `FlowOpsMasterListener.onRegister`（:29）无脑接受注册，所有消息无身份验证 | L1 注册 token 校验 + L2 会话身份绑定 |
| 配置传输 | 配置生成链产物（docker-compose.yml/Dockerfile/default.conf）进 `TaskRequest.config` 随消息下发 | 不变 |
| 协议 | v0.4.0（任务 + 查询消息），MessageType 最大 `CONTAINER_LOGS_RESP = 11` | 新增产物传输消息 + Register 带 token，Minor → 0.5.0 |

> 注：HTTP 端点本有 Sa-Token 登录拦截器未放行的问题（子节点无登录态会被 401 拦下），随端点删除一并消解，无需再补 `excludePathPatterns`。

## 总体方案

### 1. 产物注册表（DB 标准化）

产物不再散落 volumeDir，主节点集中存储 + DB 登记元数据：

```sql
CREATE TABLE deploy_artifact (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  service_id   BIGINT       NOT NULL,
  deploy_name  VARCHAR(63)  NOT NULL,
  type         VARCHAR(16)  NOT NULL,   -- JAR / DIST / BINARY
  file_name    VARCHAR(255) NOT NULL,
  storage_path VARCHAR(512) NOT NULL,   -- 主节点实际存储路径
  size         BIGINT       NOT NULL,
  checksum     CHAR(64)     NOT NULL,   -- sha256，传输后校验
  version      INT          NOT NULL,   -- 每次上传 +1，支持多版本/回滚
  create_time  DATETIME,
  KEY idx_service (service_id, type)
);
```

- **存储位置不变**：产物仍在现有单点部署位置 `{volumeDir}`（jar→`app.jar`、binary→`app`、其他→原文件名、dist→`dist/` 解压目录）。注册表 `storage_path` **记录现位置**，不做存储搬迁
- **上传**：文件仍落 `{volumeDir}` → 算 size/sha256 → 插一行注册表（version+1，同服务同 type 递增）
- **请求**：子节点带 `(service_id, type, version=0 最新)` → 主节点查注册表 → 定位 `storage_path` → 分块发送
- **dist 目录**：保持解压目录，**传输时流式打包**（单目录 tar），避免存储改成双份
- **删除服务**：级联删注册表行（存储文件随 volumeDir 清理，逻辑不变）

### 2. 产物存储与注册表（app 内 service/artifact 包）

不建独立模块，在 flowops-app 内新增包 `service/artifact`，只负责存储与登记，不碰协议与部署：

```
service/artifact/
├── ArtifactStore.java            # 接口：save/load/delete/read，路径解析
├── LocalArtifactStore.java       # 实现：从 storage_path（现 volumeDir）流式读取；支持目录打包（dist 传输用）
├── ArtifactRegistry.java         # 注册表 CRUD + 版本管理（查最新/按版本）
├── entity/DeployArtifact.java
└── mapper/DeployArtifactMapper.java
```

依赖：沿用 flowops-app 现有的 spring-boot-starter + mybatis-plus。

### 3. 自定义协议分块传输（替代 HTTP）

在 nexa-protocol 新增产物传输消息（v0.5.0），复用 Envelope 的 `request_id` 关联整次传输：

```proto
// MessageType 新增：ARTIFACT_REQ=12, ARTIFACT_DATA=13, ARTIFACT_ACK=14
message ArtifactRequest {          // 子 → 主
  string service_id = 1;
  string type = 2;                 // JAR / DIST
  int32  version = 3;              // 0 = 最新
}
message ArtifactChunk {            // 主 → 子，分块
  string transfer_id = 1;          // 复用 Envelope.request_id
  int32  sequence = 2;             // 第几块，从 0
  int32  total_chunks = 3;
  int64  total_size = 4;
  string checksum = 5;             // 整体 sha256（末块携带）
  bytes  data = 6;                 // 单块 < maxFrameSize（现 10MB）
}
message ArtifactAck {              // 子 → 主
  string transfer_id = 1;
  bool ok = 2;
  string error = 3;
}
```

**流程**：子节点 START 前 → `ARTIFACT_REQ` → 主节点查注册表定位 → 流式分块发 `ARTIFACT_CHUNK` → 子节点重组 + sha256 校验 → `ARTIFACT_ACK`。块大小建议 1~2MB，保证内存有界且远小于帧上限。

### 4. 子节点认证 L1 + L2

**L1 注册认证**：每节点独立 token，主节点校验通过才建会话。

```sql
CREATE TABLE nexa_node (
  runner_id      VARCHAR(63) PRIMARY KEY,
  node_name      VARCHAR(255),
  token          CHAR(64) NOT NULL,     -- 建议 sha256 存储
  status         VARCHAR(16),           -- online / offline
  last_heartbeat DATETIME,
  create_time    DATETIME
);
```

- `RegisterRequest` 增加 `token` 字段；`FlowOpsMasterListener.onRegister` 校验 token 与 runnerId 匹配，失败则拒绝注册（不再无脑接受）
- token 的录入：主节点提供管理接口（或 SQL 初始化），runner 配置文件持有同一 token

**L2 会话身份绑定**：注册成功后 Channel↔runnerId 绑定（复用现有 `RunnerSession`），之后**所有操作按会话身份做权限校验**：

| 操作 | 校验规则 |
|------|---------|
| 产物请求 | 仅允许请求**分配给该节点**服务的产物（`deploy_service.node_id = runnerId`，含 auto 已指派） |
| 任务回执 | 仅接受 `PendingTask.nodeId == 会话 runnerId` 的回执（`RemoteTaskManager` 校验） |
| 状态/日志查询 | 仅允许查询分配给该节点服务的状态/日志 |
| 心跳 | 归入会话身份 |

## 开发顺序总览

```
步骤 1  nexa-protocol：认证 + 产物传输消息（契约基础，无前置）
步骤 2  后端        ：注册表/存储 + 上传登记 + 移除 HTTP + 服务端传输 + 注册认证（依赖 1）
步骤 3  子节点      ：配置 token + 客户端传输 manager（依赖 1）
步骤 4  联调        ：产物经协议下发 + 认证生效
```

> 步骤 1 先行；步骤 2、3 依赖 1，可并行。前端（nodeId 等）见 2026-08-11 计划步骤 6，不受本文影响。

| 步骤 | 内容 | 仓库 | 前置依赖 |
|------|------|------|---------|
| 1 | Register+token、ARTIFACT 消息 + codec + handler | nexa-protocol | 无 |
| 2 | 注册表/存储 + 上传登记 + 删 HTTP + 服务端传输 + 注册认证 | flowops（后端） | 1 |
| 3 | token 配置 + 客户端传输 + 任务执行时拉产物 | flowops-executor | 1 |

## 开发步骤（按顺序）

### 步骤 1：协议 — 认证与产物传输消息（契约基础）

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `proto/register.proto` | `RegisterRequest` 增加 `string token = 5` |
| 修改 | `proto/common.proto` | `MessageType` 加 `ARTIFACT_REQ=12 / ARTIFACT_DATA=13 / ARTIFACT_ACK=14` |
| 新建 | `proto/artifact.proto` | 按上文契约定义 `ArtifactRequest / ArtifactChunk / ArtifactAck` |
| 生成 | Java + Go | 重新生成 protobuf |
| 修改 | Java `NexaMaster` | 注册 `ArtifactHandler`（处理 ARTIFACT_REQ，回调 listener） |
| 修改 | Java `NexaMasterListener` | 增加 `onArtifactRequest` 回调 |
| 修改 | Java/Go codec | `buildArtifactRequest/Chunk/Ack` 构造；块携带 request_id |
| 修改 | 版本号 | Java / Go Minor → 0.5.0 |

**验收**：`mvn install` / `go build` 通过；master 能收到子节点 ARTIFACT_REQ 并分块回发，子节点能重组。

### 步骤 2：后端 — 注册表/存储 + 上传登记 + 移除 HTTP + 服务端传输 + 注册认证（依赖 1）

| 改动 | 文件 | 说明 |
|------|------|------|
| 新建 | `service/artifact/entity/DeployArtifact.java` + `mapper/DeployArtifactMapper.java` | 对应 `deploy_artifact` 表 |
| 新建 | `service/artifact/ArtifactStore.java` / `LocalArtifactStore.java` | 文件存取接口 + 本地实现（从现 volumeDir 流式读取、sha256 计算、**目录打包**供 dist 传输） |
| 新建 | `service/artifact/ArtifactRegistry.java` | 注册表 CRUD：按 (serviceId,type) 查最新/按版本、上传登记、删除 |
| 修改 | `sql/migration/` | 新增 `V3_1_0__create_deploy_artifact.sql`（建表） |
| 修改 | `service/deploy/UploadHelper.java` | 上传仍落 `{volumeDir}`（位置不变），额外登记 `ArtifactRegistry`（算 size/sha256，version+1）；dist 保持解压目录 |
| 删除 | `controller/ArtifactDownloadController.java` | 移除 HTTP 端点 |
| 修改 | `service/deploy/RemoteDeployDispatcher.java` | 移除 `buildArtifactUrl` 填充（产物改由子节点主动拉取） |
| 新建 | `service/artifact/ArtifactTransferManager.java` | 收到 `ARTIFACT_REQ` → 查注册表 → `ArtifactStore` 流式分块发送 |
| 修改 | `config/master/FlowOpsMasterListener.java` | `onRegister` 校验 token（查 `nexa_node`）不再无脑接受；`onArtifactRequest` → 交 ArtifactTransferManager；L2 校验会话身份 |
| 新建 | `entity/NexaNode.java` + mapper | 对应 `nexa_node` 表 |
| 修改 | `sql/migration/` | 新增 `V3_1_1__create_nexa_node.sql`（建表 + 可选初始化数据） |
| 修改 | `service/node/RemoteTaskManager.java` | 回执校验会话 runnerId 与 PendingTask.nodeId 一致 |

**验收**：上传后注册表有记录；子节点 `ARTIFACT_REQ` 能拿到分块数据；错误 token 注册被拒绝；伪造身份回执被丢弃。

### 步骤 3：子节点 — token 配置 + 客户端传输（依赖 1，可与步骤 2 并行）

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `config/config.go` + 配置文件 | runner 增加 `token` 配置 |
| 修改 | `runner/runner.go` | 注册时携带 token |
| 新建 | `runner/artifact.go` | `ArtifactTransferManager`：发 `ARTIFACT_REQ` → 收分块重组 → sha256 校验 → `ARTIFACT_ACK` |
| 修改 | `runner/task.go` | `executeTask` 的 START 动作：先按需拉产物（JAR/DIST）→ 解压/落盘 → 再写 config → 构建 |

**验收**：注册携带正确 token 成功；START 前能经协议拉到产物并校验通过。

## 可行性分析

| 维度 | 现状 | 结论 |
|------|------|------|
| 注册表 | 有 MyBatis-Plus 基建，app 内新增表 + service 即可 | ✅ 工作量小 |
| 协议传输 | Envelope.request_id 已支持关联；帧上限 10MB，分块 < 上限 | ✅ 可行 |
| 认证 | `RunnerSession` 已有会话绑定；只需补注册校验 + 操作鉴权 | ✅ 可行 |
| 移除 HTTP | 端点删除，无遗留依赖（Sa-Token 问题一并消解） | ✅ |

### 风险与关键问题

| # | 风险点 | 影响 | 应对 |
|---|--------|------|------|
| 1 | **帧上限与块大小**：单块必须 < `maxFrameSize`(10MB) | 中 | 块设 1~2MB，流式发送内存有界 |
| 2 | **token 发放管理**：nexa_node 需先录入 token，runner 配置需同步 | 中 | 管理接口或 SQL 初始化；文档化录入流程（待决策 2） |
| 3 | **L2 会话校验覆盖不全**：所有操作都要按会话身份鉴权，遗漏即认证形同虚设 | 中 | 步骤 3 明确逐操作校验表（产物/回执/查询/心跳） |
| 4 | **dist 传输时打包**：每次传输重复打包 | 低 | 产物变更才重新上传，传输打包开销可接受 |
| 5 | **多版本产物**：version 递增，磁盘占用增长 | 低 | 保留最近 N 版，历史可删（待决策 4） |
| 6 | **sha256 校验失败**：传输损坏需重拉 | 低 | 子节点 ACK 返回失败并重发 REQ；重试细节归协议项目后续支持 |

### 结论

**方案可行**。注册表 + 协议分块传输 + L1/L2 认证均为增量实现，无架构性阻碍。改动集中在：协议加消息（步骤 1）、后端注册表/传输/鉴权（步骤 2）、runner 传输与 token（步骤 3）。

## 关键决策记录

| 决策 | 结论 | 原因 |
|------|------|------|
| 产物管理 | DB 注册表 `deploy_artifact` 记录元数据，`storage_path` 指向现 volumeDir 位置 | 存储不搬迁，传输按 DB 定位 |
| 产物传输 | 自定义协议分块（ARTIFACT_REQ/CHUNK/ACK），复用 request_id | 不走 HTTP；分块内存有界；自有协议统一 |
| 认证层级 | L1（注册 token）+ L2（会话身份绑定） | 覆盖「所有操作需身份验证」，改动最小 |
| 重连/重发 | 不在本计划 | 后续单独在 nexa-protocol 支持 |
| 存储/注册表位置 | flowops-app 内 `service/artifact` 包，不建独立模块 | 避免模块膨胀，存储与注册表在 app 内即可 |
| dist 形态 | 保持解压目录，传输时流式打包 | 存储位置不变，避免双份 |

## 里程碑

| 阶段 | 内容 | 完成标志 |
|------|------|---------|
| M1 | 协议消息（步骤 1）+ 后端注册表/传输/认证（步骤 2） | 两端编译通过；上传产生注册表记录 |
| M2 | runner 传输与 token（步骤 3）+ 联调 | 子节点经协议拉到产物并校验通过；错误 token 注册被拒 |
| M3 | L2 全量鉴权生效 | 伪造身份回执/越权产物请求被丢弃 |

## 验证方式

```bash
# 步骤 1（协议）
cd D:\project\mix\nexa-protocol\java && mvn install
cd D:\project\mix\nexa-protocol\go && go build ./...

# 步骤 2（后端）
cd D:\project\backend\flowops && ./mvnw compile
# 上传产物 → 查 deploy_artifact 有记录，storage_path 文件存在

# 步骤 2 + 3（联调）
cd D:\project\go\flowops-executor && go build -o flowops-executor.exe .
# runner 配 token 注册 → master 接受；错误 token → 拒绝
# 远程部署 START：runner 发 ARTIFACT_REQ → 分块收到产物 → sha256 通过 → 构建启动
# 伪造 runnerId 回执 → master 丢弃

# 回归：本机部署不受影响；状态/日志查询（2026-08-11 步骤 4）正常
```

## 待决策问题

1. `nexa_node` token 录入方式：管理 API（新增 `/api/nodes` 下注册 token）/ SQL 初始化 / 两者？
2. 产物多版本保留策略：保留最近 N 版？版本回滚是否本期做？
3. 认证 token 是否每节点独立（推荐）还是全局共享？
4. dist 传输时流式打包用 tar 还是 zip？（tar 与现有产物传输一致）
