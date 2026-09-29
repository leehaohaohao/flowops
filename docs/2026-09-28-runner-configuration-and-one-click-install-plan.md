# 执行器配置统一与节点一键安装修订计划

> 状态：**配置与凭据边界已定稿；O1 具体 API 契约及代码仍待实施**。本计划修订[阶段 2 发布包分发](2026-09-27-runner-package-distribution-plan.md)的 P2 配置构建方式，以及[阶段 3 安装与启动](2026-09-27-runner-start-and-node-operations-plan.md)的 O1—O5 凭据交付方式。当前 `deploy/release-config/`、`go:embed config.*.yaml`、`executor.env` 模板和“安装时再次输入原 token”仍是旧实现/旧设计；不得把本计划描述为已上线功能。

## 已定稿的跨仓契约

发布构建只需要维护每个环境的一份**非敏感配置源**：执行器仓库新增受版本控制的 `config/profiles/prod.yaml`、`config/profiles/dev.yaml`，分别保存心跳、重连、日志等允许公开的默认值。`deploy/release-config/config.{prod,dev}.yaml` 删除，发布构建不再从本地私有的 `config/config.{prod,dev}.yaml` 复制默认值。后两者只作为人工运行的外部覆盖文件兼容，不再被 `go:embed` 编译；现有本机私有文件不覆盖、不自动删除。发布脚本的 YAML 读取、白名单校验、生成内嵌配置由**同一个 Go 工具**承担，Shell 与 PowerShell 只调用它，避免维护两份转换逻辑。

Linux 构建入口 `deploy/release.sh --version 0.7.0 [--env prod|dev]`，Windows 构建入口 `deploy/release.ps1 -Version 0.7.0 [-Environment prod|dev]`，缺省均为 `prod`。**每次只将所选环境的非敏感默认值编入二进制及其镜像**，不把 YAML 增加为 tar 成员；发布包仍为格式 v1 的六个成员、同时支持 native/container。构建产物记录所选环境；运行时 `APP_ENV`/`--env` 与构建环境不一致时拒绝启动，避免无意使用另一环境的默认值。`dev` 包的 `package.version` 必须采用 `-dev` 预发布后缀（例如 `0.7.0-dev`），文件名、镜像标签、内嵌 `runner.version` 均使用该版本；`prod` 不允许 `-dev` 后缀。发布包身份仍以整个 tar.gz 的 SHA-256 为准，同版本、同环境、同镜像载荷与同构建输入必须可重复。

通用包**不包含** `runner.id`、`runner.master_addr`、注册 token、数据库口令或目标机专属文件路径。构建工具对内嵌配置执行字段白名单校验并生成空配置启动、敏感值字节扫描的验收证据；不能只靠文本替换碰巧清空三个字段。生产和开发环境的节点专属值在安装时提供，不作为发布构建输入。手工启动仍可用现有 CLI/ENV/`--config` 覆盖；托管安装固定走下述目标机配置文件。

节点管理页“一键分发并安装”由主节点串行协调**阶段 2 分发**与**阶段 3 安装**。阶段 2 单独的分发 API 仍只传六成员通用包，不写节点凭据、不解压、不启动；这条边界保持不变。阶段 3 从已登记节点取得 `runnerId`，从已校验 manifest 的 `package.version` 取得版本并按 `-dev` 后缀判定构建环境（带后缀为 dev，其余为 prod），从主节点新配置 `flowops.runner.master-advertise-address` 取得**子节点可访问**的 `host:port`（不得从浏览器 URL、SSH 地址或 `NEXA_MASTER_HOST=0.0.0.0` 推断）。主节点在首次安装时生成 32 字节密码学安全随机 token，只保存 SHA-256，并把明文通过已校验主机密钥的 SSH/SFTP 写到目标机；管理员无须再次输入登记时的 token。由于当前登记 API 要求提供 token，首次托管安装会**轮换**该登记 token；安装前节点必须离线且没有活动安装，页面明确显示这次轮换的影响。升级/重启沿用目标机既有凭据，不旋转；凭据丢失走单独的受控重置流程。

目标机凭据为**节点专属 YAML**，路径固定为 `/opt/flowops/runner/private/<nodeKey>.yaml`，其中 `<nodeKey>` 是 UTF-8 `runnerId` 的 SHA-256 小写十六进制，避免未校验 runnerId 成为路径片段。目录 `0700`、文件 `0600`，由预置的 SSH 管理账户持有；文件不在不可变的 `/opt/flowops/runner/releases/<sha256>/` 内。内容只含 `runner.id`、`runner.master_addr`、`runner.token`；`runner.version` 继续从 manifest 以 `FLOWOPS_RUNNER_VERSION` 注入，`APP_ENV` 使用包的构建环境。后端以 YAML 序列化并经 SFTP 写同目录临时文件、设权限、原子改名；禁止把 token 拼进远端命令、命令行参数、URL、Compose 文本、日志或 API 响应。

native 的 systemd 单元用 `--config <目标机路径>` 读取该文件；container 的 Compose 模板只读挂载为容器内固定路径，并用 `FLOWOPS_CONFIG` 指向它。模板不再通过 `EnvironmentFile`/`env_file` 向容器环境变量注入 token；这样 `docker inspect` 不直接展示 token。目标机 root、Docker 管理员及能读取该文件的账户仍有读取能力，因此目标机权限与日志脱敏依然是安全边界。

首次安装顺序：校验超级管理员、节点离线、无活动安装、SSH 最近测试 `CONNECTED`、包已分发且摘要正确、master advertise 地址已配置 → 生成新 token → SFTP 暂存 `0600` 配置 → 写登记表新 token 哈希并记录安装作业 → 原子激活配置 → 安装/启动 → 等待**相同 runnerId**注册并心跳在线。跨 SSH 与数据库不存在原子事务：若哈希更新后远端激活/启动失败，作业必须标记失败，保留可诊断的阶段及重试入口；不能回退为旧 token 或显示已接入。再次尝试生成新 token 并覆盖未激活的临时配置，旧 token 始终无效。更新已运行节点的凭据属于独立“重置凭据”操作，不由升级或重启隐式触发。

| 仓库绝对路径 | 契约负责人 | 本次范围 |
| --- | --- | --- |
| `D:\project\backend\flowops` | 协调者定稿发布/安装契约；主节点分发编排、凭据生成与登记哈希更新 | 本文档、发布包格式文档、阶段 2/3 计划及后续 O1/O3 实现 |
| `D:\project\go\flowops-executor` | 单一环境配置源、构建环境选择、无密钥发布包、双模式配置文件模板 | `config/**`、`deploy/**`、说明与对应测试 |
| `D:\project\front\flowops-front` | 节点页一键操作和安装状态呈现 | 节点管理 API/页面/类型/测试 |
| `D:\project\mix\nexa-protocol` | 无 | 不修改 |

## 开发顺序表

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C0 | `D:\project\backend\flowops`（协调者） | 定稿本计划、[发布包格式](runner-package-format.md)与阶段 3 O1 的配置文件/API 契约；固定 token 轮换、失败重试、advertise 地址及包环境规则 | 无 | 本计划、相关 `docs/**` | 三仓一致的字段、路径、状态和操作顺序 | 对照当前登记表仅存哈希、现有 SSH 信任边界和包格式 v1 | 不改任何仓库代码 |
| C1 | `D:\project\go\flowops-executor`（阶段 2 P2 修订） | 将 prod/dev 非敏感默认值收敛到 `config/profiles/**`，移除 `deploy/release-config/**` 重复源；共享 Go 配置生成器供 Shell/PowerShell 调用；新增打包环境参数默认 prod，只编入所选环境并禁止运行时切换；保留 CLI/ENV/外部 YAML 覆盖 | C0 | `config/**`、`deploy/release.sh`、`deploy/release.ps1`、`deploy/packager/**`、`deploy/README.md`、`README.md`、对应测试 | 六成员、无节点秘密、可选择环境的双模式发布包 | prod 缺省、dev 显式、环境错配拒绝；包内/镜像内敏感值扫描；相同输入摘要一致；镜像内 CLI/Compose 可运行 | 不把 token/地址/runnerId 编进二进制或归档，不改主节点/协议 |
| C2 | `D:\project\go\flowops-executor`（阶段 3 O2） | 将 systemd 与 Compose 模板改为读取目标机专属 YAML；容器只读挂载配置文件且只暴露非敏感的配置路径/环境与版本 | C0、C1、阶段 3 O1 | `deploy/templates/**`、模板测试和说明 | 不含节点凭据的两种可渲染模板 | `systemd-analyze verify`、`docker compose config`、容器 `docker inspect` 不出现 token 值、两模式均能读配置 | 不生成 token、不写目标机配置、不改 runner 任务行为 |
| C3 | `D:\project\backend\flowops`（阶段 3 O3） | 增加 `flowops.runner.master-advertise-address` 校验；安装作业通过 SSH/SFTP 写节点 YAML、首次自动轮换 token 哈希、启动并等待注册；升级/重启不轮换，失败可安全重试；提供离线节点的受控“重置凭据”作业；后端串行编排阶段 2 分发与阶段 3 安装 | 阶段 2 P3/P5、C0、C2 | `flowops-app` 节点安装 controller/service/DTO/mapper/entity、配置、迁移、对应测试 | 超级管理员可发起一键分发安装、重置丢失凭据并看到明确阶段/结果 | 未配地址、节点在线、包错误、SSH 中断、哈希更新后启动失败、重复请求、错误 runnerId、秘密不入日志/DB/命令行、注册超时 | 不把 token 放发布包，不自动安装 Docker，不执行普通业务项目部署 |
| C4 | `D:\project\front\flowops-front`（阶段 3 O4） | 节点管理页提供“分发并安装”、模式选择、token 将轮换的提示、作业阶段及离线节点“重置凭据”入口；不要求再次输入 token，重启/升级沿用既有凭据 | C0、C3 API 定稿 | `src/pages/NodeList.tsx`、`src/api/nodes.ts`、`src/types/**`、相关测试 | 页面一键完成受控操作并区分“安装完成/节点在线” | 无权限入口隐藏、在线节点阻止首次安装/重置、重复点击、失败阶段/重试与注册等待展示 | 不在业务项目页安装执行器，不在页面保存/展示 token |
| C5 | `D:\project\backend\flowops`（协调者集成验收） | 在实际 Linux 主机从页面完成 prod 包分发、native/container 各一次安装与上线；验证升级/重启不轮换、失败重试、凭据权限和日志脱敏；开发包另验环境隔离 | C1—C4、阶段 1 S4 | 本计划与阶段 2/3 验收记录；缺陷回流对应行 | 真实 SSH、SFTP、Nexa 注册、Docker/systemd 与权限证据 | 包摘要一致、文件 `0600`、目标配置不在 release 内、token 不见于包/inspect/日志、同 runnerId 上线 | 不以模拟测试、仅启动命令返回 0 或单纯 TCP 连通代替验收 |

受派实现者只修改本行仓库与允许范围。C0 由协调者先定稿；C1 保持阶段 2 包格式 v1，C2—C4 属阶段 3，不能因 C1 完成就声称一键安装可用。阶段 1 S4 与阶段 2 P5 未真实验收时，C3—C5 不得标完成。
