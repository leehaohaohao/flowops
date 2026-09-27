# 阶段 4：子节点宿主机 Docker 部署闭环

> 状态：待实施。前置：[阶段 3 双模式启动](2026-09-27-runner-start-and-node-operations-plan.md)已完成真实环境验收。本阶段修复“子节点在线，但部署回执找不到 Docker 命令”并验证业务容器的实际落点。

## 执行与能力契约

`RemoteDeployDispatcher` 当前把主节点保存的 `service.volumeDir` 原样放入 `TaskRequest.volume_dir`；`flowops-executor` 在该绝对路径写入产物、Dockerfile 与 Compose 文件，再执行 `docker compose`。首版固定主节点 `app.storage.path=/data/flowops/services`，Linux 子节点宿主机和执行器容器也使用同一绝对根路径。主子节点各自存储文件，产物仍经 Nexa Protocol 传输。需盘点已有服务的 `volumeDir`；不在该根目录的服务先完成显式迁移，不自动把远端任务写到任意目录。

容器模式必须同时有镜像内 Docker CLI/Compose、宿主机 Docker socket 和 `/data/flowops:/data/flowops` 同路径读写挂载。原生模式使用宿主机 Docker CLI/Compose 和 daemon 权限。子节点检查这些条件，心跳报告 `docker_ready` 和机器可读错误码；主节点把“连接在线”与“可部署”分开，自动选节点只选可部署节点，指定不可部署节点时在任务下发前返回明确错误。旧协议客户端缺少能力字段时视为不可部署。任务执行前仍重新检查一次，避免心跳之后 daemon 失效。Docker socket 赋予宿主机 Docker 管理能力，只授予受信任节点，不用 Docker-in-Docker 或 `--privileged`。

本阶段不实施跨节点 Docker 网络，不通过 SSH 逐条执行项目部署，也不把子节点本身建为普通项目。现有项目 `nodeId` 仍选择目标执行器，SSH 仅用于阶段 1—3 的执行器管理。

| 仓库绝对路径 | 契约负责人 | 本阶段范围 |
| --- | --- | --- |
| `D:\project\mix\nexa-protocol` | 心跳能力字段与兼容规则 | Protobuf、Java/Go SDK 及测试 |
| `D:\project\go\flowops-executor` | 本地 Docker 就绪检查和安全路径 | `runner/**`、`config/**`、测试和部署说明 |
| `D:\project\backend\flowops` | 可部署状态、选择与任务前校验；协调者定稿能力契约 | `flowops-app` 节点和远程部署模块、测试 |
| `D:\project\front\flowops-front` | 展示能力和原因 | 节点管理页/节点选择控件及测试 |

## 开发顺序表

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D1 | `D:\project\backend\flowops`（协调者） | 固定 `docker_ready`、错误码、心跳过期、旧客户端兼容、`volumeDir` 根目录与迁移规则；盘点真实服务路径 | 阶段 3 O5 | 本文档、契约文档；只读核查配置/服务数据 | 四仓一致的能力与路径契约、旧服务迁移清单 | 对照一条失败任务的 `volumeDir`、目标宿主机目录和主节点 `app.storage.path` | 不实现代码或自动迁移 |
| D2 | `D:\project\mix\nexa-protocol` | 给 `HeartbeatRequest` 追加 `docker_ready` 和 `docker_error_code`，保留现有字段编号、连接身份校验及断开语义；更新 Java/Go 生成代码和构造方法 | D1 | `proto/heartbeat.proto`、Java/Go codec 与生成代码、相关测试 | 发布新协议构件；旧客户端缺字段解析为 `false` | 新旧报文兼容、鉴权与现有心跳回归 | 不修改 TaskRequest 路径语义或注册认证 |
| D3 | `D:\project\go\flowops-executor` | 检查 CLI、Compose、daemon、存储根可读写并报告 D2 能力；任务前再检查；只接受允许根下的真实路径，拒绝 `..` 和符号链接逃逸；状态/日志查询沿同一 Docker 环境执行 | D1、D2 | `runner/**`、`config/**`、相关测试和 `README.md` | 不具备 Docker 能力仍可显示在线，但部署/查询返回具体原因 | 缺 CLI、缺 socket、daemon 不通、目录只读、越界路径、容器/原生两种方式 | 不运行第二个 daemon，不修改协议仓库 |
| D4 | `D:\project\backend\flowops` | 按 D2 心跳维护有时效的可部署状态；`auto` 过滤不可部署节点，指定节点下发前校验；API 返回能力与错误码；记录失败任务的准确原因 | D2、D3 | `flowops-app/src/main/java/com/nexa/flowops/service/node/**`、`service/deploy/**`、节点 DTO/API 和测试 | 在线与可部署分离，任务不会下发给已知不可部署节点 | 旧客户端、过期心跳、断线、自动/指定选点测试 | 不在本行改协议、子节点或 UI |
| D5 | `D:\project\front\flowops-front` | 节点管理页展示在线/可部署双状态和错误原因；项目配置选点时标明当前不可部署，允许保存已登记的离线目标，实际下发仍由 D4 阻止 | D4 API 定稿 | `src/pages/NodeList.tsx`、服务编辑的节点选择控件、`src/api/nodes.ts`、`src/types/**`、相关测试 | 用户能识别通信正常但 Docker 不可用的节点 | 状态刷新、失联、错误码中文展示与部署前提示 | 不新增 SSH 执行项目任务 |
| D6 | `D:\project\backend\flowops`（集成验收） | 用真实主节点、Go 子节点、数据库与 Docker，分别在 native/container 模式启动一个测试业务服务，再查询状态/日志并停止、删除；在目标宿主机核对业务容器与构建文件 | D3、D4、D5 | 本文档验收记录；缺陷回流对应行 | 两种模式均通过的任务 ID、日志、宿主机 `docker ps` 和目录证据 | 故意移除 socket 后显示不可部署且不派发；恢复后可正常部署 | 不以模拟客户端或仅心跳在线替代验收 |

各行受派代理只编辑该行仓库及允许范围。D1 契约由协调者最终确定；D6 通过前不得标记“子节点宿主机部署已修复”。
