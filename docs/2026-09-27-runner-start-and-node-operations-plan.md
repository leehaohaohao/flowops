# 阶段 3：SSH 安装、双模式启动与节点管理操作

> 状态：待实施。前置：[阶段 2 发布包分发](2026-09-27-runner-package-distribution-plan.md)已通过真实链路验收。本阶段使主节点能管理执行器生命周期，不把执行器当作普通业务服务项目。

## 安装与运行契约

安装对象必须先在 `nexa_node` 登记、完成 SSH 设置，并已有阶段 2 校验通过的发布包。节点管理页选择 `native`（宿主机进程）或 `container`（Docker 容器），同一 `runnerId` 只能有一种活动安装方式。主节点通过 SSH 在目标机解包到 `/opt/flowops/runner/releases/<sha256>/`，校验 manifest 与成员摘要，使用固定名称管理 systemd unit 或 Compose 项目。操作均按 `runnerId` 和当前已记录安装标识定位，不提供任意远端命令输入框；停止/卸载执行器不能删除该机上的业务容器与 `/data/flowops/services` 数据。

`native` 使用包内二进制和 systemd，目标机必须已有 Docker CLI、Compose 插件、可访问的宿主机 daemon，以及可写 `/data/flowops`。`container` 使用包内镜像 tar，经远端 `docker load` 后启动执行器容器；容器挂载宿主机 `/var/run/docker.sock` 和 `/data/flowops:/data/flowops`，镜像内已有 CLI/Compose。两种方式均使用**目标宿主机 Docker**，不使用 Docker-in-Docker。目标机 SSH 管理账号必须预先具备非交互执行所需 systemd/Docker 操作的权限；主节点不负责安装 Docker 或改动远端 sudoers。

现有 `nexa_node.token` 仅保存 SHA-256，主节点无法取回明文。首次安装时由超级管理员在安装表单输入当前注册 token；主节点对输入值计算 SHA-256 与登记值比对，通过后只在该次操作内使用，经 SSH 写入远端权限 `0600` 的环境文件。API、数据库、审计和日志不得保存或回显明文。若已遗失 token，先使用现有节点登记编辑操作重设，再安装。后续重启读取远端环境文件，不要求再次输入 token。

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
| O1 | `D:\project\backend\flowops`（协调者） | 定稿安装模式、状态、固定远端路径/资源名称与 API：安装、启动、停止、重启、卸载、读取受限日志；定义 token 一次性传入与等待注册超时 | 阶段 2 P5 | 本文档与 API 契约文档；只读核查节点注册表和实时会话 | 后端/前端可独立开发的操作契约 | 核对现有 `runnerId` 长度、token 哈希、节点在线来源和目标机权限 | 不实现远端安装，不改协议 |
| O2 | `D:\project\go\flowops-executor` | 固定包内 systemd 与 Compose 模板的环境文件位置、挂载、重启策略、资源名称；容器模板不得启动 daemon，也不使用 `--privileged` | O1 | 阶段 2 新增的 `deploy/**`、发布构建脚本、相关测试 | 可渲染且无 token 明文的两种安装模板 | `systemd-analyze verify`、`docker compose config`；Docker 容器内 CLI/Compose 可运行 | 不改 Go 的任务执行、连接管理或协议 |
| O3 | `D:\project\backend\flowops` | 通过 SSH 实现受控安装和生命周期操作，记录 mode/packageSha/安装状态/作业结果；校验 token 哈希后只写远端环境文件；安装/启动后等待相同 runnerId 注册，失败报告具体阶段；卸载仅删除执行器自有资源 | O1、O2 | `flowops-app` 新增节点安装 controller/service/DTO/mapper/entity、SQL 迁移、测试 | 超级管理员可调用的安装、启动、停止、重启、卸载、受限日志 API | token 错误、包未分发、旧模式仍运行、远端操作失败、注册超时、普通用户 403、卸载不触及业务容器 | 不通过 SSH 执行普通业务项目任务，不自动安装 Docker |
| O4 | `D:\project\front\flowops-front` | 节点登记行增加“安装子节点”与安装方式选择；安装后显示方式、包版本、安装/在线状态，并提供启动、停止、重启、卸载、日志操作；按真实 API 权限控制 | O3 API 定稿 | `src/pages/NodeList.tsx`、`src/api/nodes.ts`、`src/types/**`、相关测试 | 节点管理页完成执行器生命周期操作 | 正确隐藏无权限按钮、双模式互斥、操作中禁重复提交、错误原因可见 | 不在普通服务创建/编辑页新增“部署子节点”流程 |
| O5 | `D:\project\backend\flowops`（集成验收） | 两台或同一台目标 Linux 主机分别验证 native/container 安装、上线、停止、重启、卸载；核对 runnerId 唯一、token 不泄漏、业务容器和服务数据未被清理 | O2、O3、O4 | 本文档验收记录；缺陷回流对应行 | 两种启动方式的真实 SSH 和 Nexa 注册证据 | 登录目标机检查 systemd/容器、主节点在线状态、日志与卸载边界 | 不以“启动命令返回 0”替代上线验收 |

各行受派代理只编辑该行仓库及允许范围。O1 契约先由协调者定稿。O5 通过后才开始[阶段 4 宿主机 Docker 部署闭环](2026-09-27-runner-host-docker-deployment-plan.md)。
