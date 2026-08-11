# 分布式任务下发（Docker 执行抽离子节点）可行性分析

## Context

FlowOps 当前是单机部署：所有 Docker 操作（`docker compose up/down/stop/restart/logs`）都在主节点本地通过 `DockerClient`（flowops-docker）执行。目标是**把 Docker 执行能力抽离到子节点**：主节点只负责编排（数据库、配置生成、任务下发、结果持久化），子节点（Go runner）负责实际执行 Docker 命令。

主从通讯已逐步引入：主节点已接入 nexa-protocol Master（Netty, 端口 9090），能接收子节点注册/心跳/断开；子节点 flowops-executor（Go）已实现注册 + 心跳。当前缺口是**任务下发链路**（主 → 子 → 主 的请求/响应闭环）。

本计划做**可行性分析 + 任务划分**。各仓库的实现可分别派发给对应代码 agent，本文按仓库明确划分了「谁负责做什么」，并给出验收标准。

## 现状分析

### 涉及仓库

| 仓库 | 路径 | 角色 | 语言 |
|------|------|------|------|
| nexa-protocol | `D:\project\mix\nexa-protocol` | 通讯协议（proto 定义 + Java/Go SDK） | proto / Java / Go |
| flowops | `D:\project\backend\flowops` | 主节点（连 DB、部署引擎、配置生成链） | Java |
| flowops-executor | `D:\project\go\flowops-executor` | 子节点 runner（执行 docker） | Go |

### 各端能力现状

**协议（nexa-protocol, v0.1.1 Java / v0.2.0 Go）**
- `MessageType` 枚举仅覆盖生命周期消息：`REGISTER_REQ/RESP`(1/2)、`HEARTBEAT_REQ/RESP`(3/4)、`DISCONNECT_REQ`(5)
- `Envelope` 已预留 `request_id / source_id / target_id / timestamp`，天然支持请求/响应关联
- 传输层已支持双向：主 → 子 `sendTo(runnerId, Envelope)` / `broadcast(Envelope)`；子 → 主 `ReadEnvelope()`

**主节点（flowops）**
- `FlowOpsMasterListener` 接收注册/心跳/断开，仅打日志 + 自动接受注册（`config/master/FlowOpsMasterListener.java`）
- `NodeService` / `NodeController` 暴露只读节点状态 API（`GET /api/nodes`）
- `DeployExecutorService` 通过 `DockerClient` **本地**执行 docker；配置由 `ConfigGeneratorChain`（Dockerfile → Nginx → Compose）本地生成
- `deploy_record` 在本地持久化部署记录

**子节点（flowops-executor）**
- `runner/runner.go` 仅实现 connect → register → 定时心跳 → disconnect
- `ReadEnvelope()` 接口已暴露但**未被调用**，无任务接收循环
- `go.mod` 用 `replace` 指向本地 nexa-protocol，协议更新后重新构建即可生效

## 总体方案与通讯契约

### 消息流

```
主节点 (flowops, Java)                          子节点 (flowops-executor, Go)
┌─────────────────────────────┐                ┌─────────────────────────────┐
│ DB: serviceConfig / deploy   │                │                             │
│ 配置生成链 → 产物(配置+文件)  │                │ 写配置落盘 → docker compose  │
│         │                    │  TASK_DISPATCH │            │                 │
│  DeployExecutorService       ├───────────────►│  handleTask() 循环读取       │
│  ┌───────────────────────┐   │  (sendTo)      │                             │
│  │ 本机执行 │ 远程执行    │   │                │                             │
│  └───────────────────────┘   │  TASK_DISPATCH │            │                 │
│  onTaskResult → 写deploy_record│◄──────────────┤  执行结果回执                │
└─────────────────────────────┘                └─────────────────────────────┘
```

### 协议契约（所有仓库共同遵守）

**新增消息类型**（`proto/common.proto` `MessageType` 枚举）：
- `TASK_DISPATCH_REQ = 6`
- `TASK_DISPATCH_RESP = 7`

**新建 `proto/task.proto`**（业务配置全部收敛进 `config` map 字段）：

```proto
message TaskRequest {
  string task_id = 1;        // 关联回执，复用 Envelope.request_id
  string service_id = 2;
  string deploy_name = 3;
  string action = 4;         // START / STOP / RESTART / REMOVE
  string volume_dir = 5;     // 子节点落盘根目录
  map<string, string> config = 6;   // 所有配置：key=相对文件路径或配置名, value=内容/JSON
  string artifact_url = 7;   // 产物拉取地址（预留）
}

message TaskResponse {
  string task_id = 1;
  string runner_id = 2;
  bool success = 3;
  int32 exit_code = 4;
  string output = 5;         // 命令输出（截断后）
  string error = 6;
}
```

**config map 的 key 约定**：
- 相对文件路径 → value 为文件内容（runner 遍历 map 直接写盘，覆盖 Dockerfile / nginx / docker-compose.yml）
- 元数据名 → value 为 JSON 字符串（如 `service_config`、`port_mappings`）

---

## 各仓库任务包

> 每个任务包可独立交给一个代码 agent。**边界原则**：nexa-protocol 只定义协议和 SDK，不碰业务；flowops-executor 只做子节点执行，不碰主节点；flowops 只做主节点编排，不直接改协议（只消费新 SDK）。

### 任务包 1：nexa-protocol（协议仓库，先行）

**负责**：定义任务下发消息契约、重新生成两端代码、让 Java Master SDK 具备任务回执处理能力。**不负责**：任何业务逻辑（不写部署逻辑、不写 runner 循环）。

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `proto/common.proto` | `MessageType` 加 `TASK_DISPATCH_REQ = 6`、`TASK_DISPATCH_RESP = 7` |
| 新建 | `proto/task.proto` | 按上文契约定义 `TaskRequest` / `TaskResponse` |
| 生成 | Java + Go 代码 | 重新生成 protobuf，Java 到 `com.nexa.protocol.*`，Go 到 `go/messages/*.pb.go` |
| 修改 | Java `master/NexaMaster.java` | handler 链当前硬编码 `List.of(Register, Heartbeat, Disconnect)`（约 :51），改为可扩展注册；注册处理 `TASK_DISPATCH_RESP` 的 handler |
| 修改 | Java `master/NexaMasterListener.java` | 增加任务回执回调（如 `onTaskResult(RunnerSession, TaskResponse)`，给默认实现） |
| 修改 | Java codec `ProtocolCodec.java` | 增加 `buildTaskDispatchRequest/Response` 构造方法 |
| 修改 | Go `codec/envelope.go` | 增加 `BuildTaskDispatchRequest/Response` |
| 修改 | 版本号 | Java Minor → 0.2.0，Go Minor → 0.3.0 |

**验收标准**
- `mvn install` / `go build ./...` 通过，两端新消息类型可用
- `NexaMaster` 收到 `TASK_DISPATCH_RESP` 时能回调 listener（不再出现 "no handler for message type"）
- `sendTo` 下发一个 `TASK_DISPATCH_REQ` 信封，Go client `ReadEnvelope()` 能正确解出 `TaskRequest`

### 任务包 2：flowops-executor（子节点 runner，与包 3 可并行）

**负责**：子节点接收任务、执行 docker、回传结果。**不负责**：主节点编排、数据库、配置生成。

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `runner/runner.go` | `Start()` 注册心跳后，启动任务接收循环：`for { env := client.ReadEnvelope(); switch type { case TASK_DISPATCH_REQ: handleTask(env) } }` |
| 新建 | `runner/task.go` | `handleTask`：解 `TaskRequest` → 遍历 `config` 写盘（key=相对路径）→ 按 `action` 执行 docker compose → 构建 `TaskResponse` 回传 |
| 修改 | `runner/runner.go` 或 `runner/task.go` | 心跳上报真实 `runningTasks/cpu/memory`（放 Phase 4 可后置） |

**实施顺序（可分期派活）**
- **Phase 1**：`handleTask` 只打日志 + 回 echo 回执（验证通讯闭环）
- **Phase 2**：写配置落盘 + 真实执行 docker，回传 exitCode/output

**验收标准**
- Phase 1：收到 `TASK_DISPATCH_REQ` 能回 `TASK_DISPATCH_RESP`，主节点能看到回执
- Phase 2：在子节点上实际完成 `docker compose up/down`，exitCode + output 正确回传
- `config` map 里的文件能按 key 落盘到 `volume_dir` 对应路径

### 任务包 3：flowops（主节点编排，与包 2 可并行）

**负责**：主节点把部署任务下发给子节点、接收回执并持久化。**不负责**：修改协议定义（只消费新 SDK）。

| 改动 | 文件 | 说明 |
|------|------|------|
| 修改 | `config/master/FlowOpsMasterListener.java` | 实现任务回执回调：按 `task_id` 关联 pending task，结果写 `deploy_record` + 日志 |
| 修改 | `service/DeployExecutorService.java` | 拆「本机执行」/「远程执行」两分支：本机走现有 `DockerClient`；远程把 `DeployContext`（配置生成链产物）打包进 `TaskRequest.config` → `sendTo(nodeId, ...)` → 维护 pending task → 回执落库 |
| 修改 | `service/NodeService.java` | 调度辅助（最少负载：按心跳 `running_tasks` 选节点） |
| 修改 | `entity/DeployService.java` + SQL | 增加节点分配字段（如 `deploy_service.node_id`），前端可选目标节点或自动调度 |
| 修改 | `dto/NodeInfoVO.java` | 补充任务数/负载信息（可选） |

**实施顺序（可分期派活）**
- **Phase 1**：`FlowOpsMasterListener` 接回执并验证（配合包 2 的 echo）
- **Phase 3**：`DeployExecutorService` 拆分支 + 节点分配字段 + 调度

**验收标准**
- 部署请求可选择目标节点（或自动调度），本机/远程两分支都工作
- 远程部署完成后 `deploy_record` 正确落库、日志可查
- 节点掉线 / 任务超时 / 执行失败的任务有明确状态标记

---

## 派活顺序与依赖

```
nexa-protocol（任务包 1）先行 —— 契约基础
        │
        ├──► flowops-executor（任务包 2）   ┐
        │                                   ├── 可并行
        └──► flowops（任务包 3）            ┘
```

- 包 1 必须先完成并 install / 更新本地 go 模块，否则包 2、3 拿不到新消息类型
- 包 2 与包 3 无相互代码依赖，可并行派活；联调时同时起两端
- 包 2、3 内部均可按 Phase 分期派活（先 echo 闭环，再真实执行 / 编排拆分）

## 可行性分析

### 技术可行性：✅ 链路基础已具备

| 维度 | 现状 | 结论 |
|------|------|------|
| 传输层双向 | `sendTo`/`broadcast` + `ReadEnvelope` 已存在 | ✅ 无需新增传输能力 |
| 请求/响应关联 | `Envelope.request_id` 已预留 | ✅ 直接复用 |
| 协议可扩展 | 协议库自研，无外部约束 | ✅ 加消息类型即可 |
| 代码生成联动 | go.mod `replace` 指向本地，Java 依赖本地 .m2 | ✅ 更新协议后两端重新构建即生效 |
| 子节点轻量 | Go 单二进制，无数据库依赖 | ✅ 符合「只执行、不存状态」定位 |

### 风险与关键问题

| # | 风险点 | 影响 | 应对 |
|---|--------|------|------|
| 1 | **产物（jar/dist 二进制）传输**：配置可以塞进消息，但二进制不可能走协议（帧上限 10MB） | 高 | 旁路传输：子节点 HTTP 拉取（artifact_url）或共享存储（NFS/MinIO）。配置与产物分离处理 |
| 2 | **契约从 proto 强类型变为两端 JSON 约定**：`config` map 的 key/value 拼写错误在运行期才暴露 | 中 | 主从两端各维护一份 `TaskConfig` DTO（Java/Go），序列化走 DTO 而非裸 map |
| 3 | **Master handler 链硬编码**：`NexaMaster.start()` 内 `List.of(...)` 需改造 | 中 | 任务包 1 中改为可扩展注册机制 |
| 4 | **日志体系**：当前容器日志在主节点本地采集（`/ws/container-logs`），子节点执行后日志归属不明 | 高 | 方案需明确：日志仍回传主节点，或日志入口改为按节点访问 |
| 5 | **节点分配与故障转移**：无 `node_id` 字段，无重试/超时/节点掉线后的任务处理 | 中 | 明确调度策略；任务超时重试；节点掉线标记任务失败 |
| 6 | **心跳上报内容为空**：`runningTasks/cpu/memory` 当前传 0 | 低 | 子节点真实上报后，才能做负载均衡调度 |
| 7 | **事务一致性**：主节点写 deploy_record 与子节点执行结果非原子 | 中 | 以子节点回执为准，主节点只做结果持久化；失败需可重放 |

### 结论

**方案总体可行**。通讯传输层、关联字段、协议可扩展性都已具备，核心工作量集中在三个任务包：协议扩展（包 1）、子节点执行（包 2）、主节点编排拆分（包 3）。

最大不确定性不在通讯本身，而在**产物传输**与**日志体系**两个横切问题，建议在 Phase 4 前决策。

## 关键决策记录

| 决策 | 结论 | 原因 |
|------|------|------|
| 子节点语言 | Go | 轻量单二进制，Go SDK 已就绪 |
| 配置传输 | 全部收敛进 `config` map<string,string> | 协议稳定，配置增删字段无需重新生成 proto |
| 控制字段 | task_id/action/success/exit_code 保持 typed | 主节点任务跟踪与结果持久化强依赖 |
| 数据库访问 | 仅主节点连 DB | 子节点无状态、无凭证、加节点零配置 |

## 阶段规划（与任务包对应）

| 阶段 | 内容 | 涉及任务包 |
|------|------|-----------|
| Phase 1 | 协议扩展 + echo 闭环验证 | 包 1 全部 + 包 2/3 的 stub 部分 |
| Phase 2 | 子节点真实 docker 执行 + 两端 TaskConfig DTO | 包 2 |
| Phase 3 | 主节点编排拆分 + 节点分配 | 包 3 |
| Phase 4 | 产物传输 + 日志体系 + 真实心跳上报 | 包 2/3 收尾 |

## 验证方式

```bash
# 包 1（协议）先落地
cd D:\project\mix\nexa-protocol\java && mvn install
cd D:\project\mix\nexa-protocol\go && go build ./...

# 包 2 + 包 3 联调
cd D:\project\go\flowops-executor && go build -o flowops-executor.exe .
cd D:\project\backend\flowops && ./mvnw compile

# 运行：起 master → 起 runner → master 下发 echo 任务 → 观察回执
```

## 待决策问题

1. 产物传输最终选型：HTTP 拉取 / 共享存储 / 两者结合？（Phase 4 前需定）
2. 子节点执行后的**容器日志**在主节点如何展示？（影响 Phase 2 的日志接口）
3. 节点分配策略：前端指定节点 / 自动最少负载 / 两者兼有？
4. `volume_dir` 语义：主节点本地路径与子节点路径是否同名，是否需要映射？
