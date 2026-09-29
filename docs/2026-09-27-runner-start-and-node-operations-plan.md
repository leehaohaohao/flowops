# 阶段 3：SSH 安装、双模式启动与节点管理操作

> 状态：待实施；阶段 2 P5 的真实链路验收仍需核对。本阶段使主节点能管理执行器生命周期，不把执行器当作普通业务服务项目。配置与凭据设计以[2026-09-28 修订计划](2026-09-28-runner-configuration-and-one-click-install-plan.md)为准。

## 安装与运行契约

安装对象必须先在 `nexa_node` 登记、完成 SSH 设置，并已有阶段 2 校验通过的发布包。节点管理页选择 `native`（宿主机进程）或 `container`（Docker 容器），同一 `runnerId` 只能有一种活动安装方式。主节点通过 SSH 在目标机解包到 `/opt/flowops/runner/releases/<sha256>/`，校验 manifest 与成员摘要，使用固定名称管理 systemd unit 或 Compose 项目。操作均按 `runnerId` 和当前已记录安装标识定位，不提供任意远端命令输入框；停止/卸载执行器不能删除该机上的业务容器与 `/data/flowops/services` 数据。

`native` 使用包内二进制和 systemd，目标机必须已有 Docker CLI、Compose 插件、可访问的宿主机 daemon，以及可写 `/data/flowops`。`container` 使用包内镜像 tar，经远端 `docker load` 后启动执行器容器；容器挂载宿主机 `/var/run/docker.sock` 和 `/data/flowops:/data/flowops`，镜像内已有 CLI/Compose。两种方式均使用**目标宿主机 Docker**，不使用 Docker-in-Docker。目标机 SSH 管理账号必须预先具备非交互执行所需 systemd/Docker 操作的权限；主节点不负责安装 Docker 或改动远端 sudoers。

现有 `nexa_node.token` 仅保存 SHA-256，主节点无法取回明文。**首次托管安装自动轮换登记 token**：主节点生成 32 字节安全随机值，只保存其 SHA-256，经严格主机密钥校验的 SSH/SFTP 写入目标机专属 `0600` YAML。超级管理员无须再次输入原 token；首次安装要求节点离线且无活动安装，页面明确提示旧 token 将失效。升级和重启读取目标机既有 YAML，不轮换 token；凭据丢失走单独的受控重置。API、数据库、审计、日志、远端命令和发布包均不得保存或回显 token 明文。

配置文件固定存放在 `/opt/flowops/runner/private/<nodeKey>.yaml`，与不可变发布版本目录分离；`<nodeKey>` 为 runnerId 的 SHA-256 小写十六进制。目录 `0700`、文件 `0600`，由预置 SSH 管理账户持有。文件仅含 `runner.id`、`runner.master_addr`、`runner.token`，通过 YAML 序列化后经 SFTP 暂存并同目录原子改名。`runner.master_addr` 只能来自显式配置的 `flowops.runner.master-advertise-address`（子节点可访问的 host:port），不能从控制台 URL、SSH 目标或 `0.0.0.0` 监听地址推导。`runner.version` 从 manifest 注入，`APP_ENV` 与发布包构建环境一致。native 的 systemd 模板使用 `--config`；container 的 Compose 模板只读挂载该 YAML 并设置 `FLOWOPS_CONFIG` 路径，不再以 `env_file` 注入 token。两种模式的运行凭据均不在发布包内；目标机 root/Docker 管理员仍有读取能力。

节点页提供“一键分发并安装”：后端作业先等待阶段 2 的包分发成功，再执行本阶段安装与注册等待，页面展示分发、安装、上线各阶段。阶段 2 单独分发 API 的行为不变，仍不安装或写凭据。安装若在更新登记哈希后失败，标记失败并保留阶段信息；重试生成新 token 并安全覆盖临时配置，不能恢复旧 token 或误显示在线。完整顺序与仓库边界见修订计划 C0—C5。

运行状态分两层：`安装状态` 来自 SSH 受控操作和远端进程/容器检查；`在线状态` 仍来自 Nexa 实时会话。启动命令成功只表示启动操作完成，必须等待相同 `runnerId` 经现有 token 注册并心跳在线，才能显示“已接入”。本阶段不新增协议字段，不以连接在线证明 Docker 业务部署成功。

| 仓库绝对路径 | 契约负责人 | 本阶段范围 |
| --- | --- | --- |
| `D:\project\backend\flowops` | 安装状态、受控 SSH 操作、权限/API；协调者定稿操作契约 | `flowops-app` 节点安装模块、迁移、测试 |
| `D:\project\front\flowops-front` | 消费操作 API | 节点管理页安装方式与生命周期操作 |
| `D:\project\go\flowops-executor` | 维护阶段 2 包内模板 | 只修正包内 systemd/Compose 模板及构建校验 |
| `D:\project\mix\nexa-protocol` | 无 | 不修改 |

## 开发顺序表

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| O1 | `D:\project\backend\flowops`（协调者） | 定稿安装/API/状态契约、子节点可达 master 地址、首次自动 token 轮换、目标机 YAML 路径与失败重试；与修订计划 C0 一致 | 阶段 2 P5、C0 | 本文档、API 契约文档；只读核查节点注册表和实时会话 | 后端/前端可独立开发的操作契约 | 核对现有 token 哈希、节点在线来源、runnerId 路径边界和目标机权限 | 不实现远端安装，不改协议 |
| O2 | `D:\project\go\flowops-executor` | systemd 模板改用 `--config` 读取目标机 YAML；Compose 模板只读挂载文件并设置 `FLOWOPS_CONFIG`，移除 token 的 `env_file` 注入；固定重启策略和资源名称 | O1、C1 | `deploy/templates/**`、发布构建脚本、模板测试 | 可渲染且不含 token 明文的两种安装模板 | `systemd-analyze verify`、`docker compose config`、`docker inspect` 不展示 token；容器内 CLI/Compose 与配置文件可用 | 不生成 token、不写目标机配置、不启动 daemon 或使用 `--privileged` |
| O3 | `D:\project\backend\flowops` | 经 SSH 实现受控安装与生命周期：首次生成 token/更新登记哈希，SFTP 写节点 YAML，启动并等待相同 runnerId 上线；升级/重启沿用文件，失败报告阶段并可重试；提供离线节点的受控凭据重置；后端串行编排包分发与安装 | O1、O2、阶段 2 P3/P5 | `flowops-app` 节点安装 controller/service/DTO/mapper/entity、配置、SQL 迁移、测试 | 超级管理员可调用的一键分发安装、启动、停止、重启、卸载、重置凭据、受限日志 API | 节点在线拒绝首次安装/重置、地址缺失、SSH 失败、哈希更新后启动失败、注册超时、token 不泄漏、普通用户 403 | 不把 token 放包内，不通过 SSH 执行普通业务项目任务，不自动安装 Docker |
| O4 | `D:\project\front\flowops-front` | 节点登记行增加“一键分发并安装”与模式选择、首次 token 轮换提示；展示分发/安装/在线状态，并提供启动、停止、重启、卸载、离线凭据重置、日志 | O3 API 定稿 | `src/pages/NodeList.tsx`、`src/api/nodes.ts`、`src/types/**`、相关测试 | 节点管理页完成执行器生命周期操作 | 权限、双模式互斥、重复提交、错误阶段与重试、在线节点不能重置、节点在线状态展示 | 不要求管理员重填 token，不在业务项目页安装执行器 |
| O5 | `D:\project\backend\flowops`（集成验收） | 真实 Linux 目标分别验证 native/container 一键分发安装、上线、停止、重启、升级、卸载；核对 runnerId、凭据权限与不泄漏、业务容器和服务数据未被清理 | O2、O3、O4 | 本文档验收记录；缺陷回流对应行 | 两种模式真实 SSH/SFTP、Nexa 注册、Docker/systemd 证据 | 文件 `0600`、token 不在包/日志/inspect、重启不轮换、上线对应 runnerId | 不以“启动命令返回 0”替代上线验收 |

各行受派代理只编辑该行仓库及允许范围。O1 契约先由协调者定稿。O5 通过后才开始[阶段 4 宿主机 Docker 部署闭环](2026-09-27-runner-host-docker-deployment-plan.md)。
