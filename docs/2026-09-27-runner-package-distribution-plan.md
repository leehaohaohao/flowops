# 阶段 2：通过 SSH 分发子节点执行器发布包

> 状态：待实施。前置：[阶段 1 SSH 连接验证](2026-09-27-node-ssh-connection-plan.md)已在真实主节点与目标机通过。本阶段只把发布包可靠送到目标机，不安装或启动子节点。

## 发布包与传输契约

`flowops-executor` 仓库产出单个 Linux amd64 发布包 `flowops-executor-<version>-linux-amd64.tar.gz`。包内固定包含 `manifest.json`、`checksums.txt`、`bin/flowops-executor`、预构建的 `images/flowops-executor.tar`、原生 systemd 模板和容器 Compose 模板。容器镜像内包含 Docker CLI 与 Compose 插件；具体 CLI/Compose 版本和镜像摘要写入 manifest。发布包不包含 runnerId、主节点地址、注册 token、SSH 私钥或目标机配置，两个运行模式使用同一个经过校验的版本。

超级管理员上传包到主节点；主节点验证清单、每个成员文件的 SHA-256、OS/架构和 1 GiB 包大小上限后，以包 SHA-256 作为不可变标识存入 `/data/flowops/runner-packages/`。分发操作只选已通过阶段 1 SSH 配置的注册节点。主节点通过 SFTP 流式上传到目标机 `/opt/flowops/runner/packages/<sha256>.part`，远端计算 SHA-256 一致后原子改名为 `<sha256>.tar.gz`，记录节点、包摘要、时间和结果。失败保留既有正式包并清理本次临时文件；相同摘要重复分发视为成功。远端目录权限由阶段 1 约定的 SSH 管理账号预先配置。

| 仓库绝对路径 | 契约负责人 | 本阶段范围 |
| --- | --- | --- |
| `D:\project\go\flowops-executor` | 发布包格式、镜像与模板 | 新增发布构建脚本、镜像、模板、校验与说明 |
| `D:\project\backend\flowops` | 包接收、验证、存储、SFTP 分发及审计；协调者定稿 manifest/API | `flowops-app` 节点包模块、SQL 迁移、测试 |
| `D:\project\front\flowops-front` | 消费主节点 API | 节点管理页上传包、选包与分发结果 |
| `D:\project\mix\nexa-protocol` | 无 | 不修改 |

## 开发顺序表

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P1 | `D:\project\backend\flowops`（协调者） | 定稿 manifest 字段、包大小上限、上传/分发/查询 API、包 SHA-256 与目标路径规则；与阶段 1 SSH 目标契约对齐 | 阶段 1 S4 | 本文档、API/发布包契约文档 | 三仓可共同使用的发布包与 API 契约 | 包清单、路径与错误码审阅 | 不编写发布包或传输代码 |
| P2 | `D:\project\go\flowops-executor` | 构建 Linux amd64 二进制和含 Docker CLI/Compose 的容器镜像，导出镜像 tar，连同 systemd/Compose 模板生成包及 SHA-256 清单 | P1 | 新增 `deploy/**`、`build/**` 或发布脚本、`README.md`、相关测试；保留 `runner/**` 行为 | 可重复构建、无密钥的发布包 | 解包后逐文件核对摘要；二进制可启动，镜像内 `docker compose version` 可执行 | 不在本行启动远端子节点，不修改主节点或协议 |
| P3 | `D:\project\backend\flowops` | 实现超级管理员上传与校验、按摘要不可变存储、SFTP 临时上传/远端校验/原子改名、分发记录与失败清理 | P1、P2 包格式定稿 | `flowops-app` 新增节点包 controller/service/DTO/mapper/entity、SQL 迁移、相关测试 | `POST /api/nodes/packages`、`POST /api/nodes/registry/{runnerId}/packages/{sha256}/distribute`、查询分发状态接口 | 正确包、篡改包、重传、连接中断、远端磁盘不足、普通用户 403；旧包未受失败影响 | 不解压、不执行包内脚本、不启动 runner |
| P4 | `D:\project\front\flowops-front` | 在节点管理页增加发布包上传、版本与摘要列表、每个节点的“分发包”及结果展示；仅超级管理员可操作 | P3 API 定稿 | `src/pages/NodeList.tsx`、`src/api/nodes.ts`、`src/types/**`、相关测试 | 页面完成包上传与指定节点分发 | 错误包提示、进度/结果、权限和重复点击验证 | 不添加启动/停止或业务服务部署入口 |
| P5 | `D:\project\backend\flowops`（集成验收） | 从实际主节点上传真实发布包并经 SSH 分发到目标 Linux 主机，核对远端摘要与正式文件路径 | P2、P3、P4 | 本文档验收记录；缺陷回流对应行 | 一次成功与一次故意中断传输的可复现记录 | 摘要一致、失败无半成品正式包、目标机尚无新进程/容器 | 不以本地 mock SFTP 测试代替真实链路 |

每行受派代理只编辑该行仓库及允许范围。P1 契约由协调者定稿；需要改包格式时先更新 P1 再做依赖行。P5 通过后才开始[阶段 3 双模式启动](2026-09-27-runner-start-and-node-operations-plan.md)。

## P1 契约定稿记录（2026-09-28 追加）

> 本节是 P1 行（`D:\project\backend\flowops`，协调者）交付的**追加记录**。
> 上文原始内容（状态行、发布包与传输契约、仓库权责表、开发顺序表）**未作任何改动**；本节的更正与补充只在此处声明，不回写上文。

### P1 交付物

| 交付物 | 内容 |
| --- | --- |
| [runner-package-format.md](runner-package-format.md) | **跨仓发布包格式契约**：命名、包内布局、`manifest.json` 字段、`checksums.txt`、归档与确定性构建、无密钥约束、主节点校验流程、与既有实现的关系 |
| [frontend-api/node-package-api.md](frontend-api/node-package-api.md) | **上传/分发/查询 API 契约**：6 个接口、请求响应字段、请求级错误文案、分发状态机、`errorCode` 中文映射、前端注意项、服务端配置项 |
| 本文件 | 计划级摘要、决策记录、与阶段 1 的对齐、分发执行顺序、前置与边界 |

P1 行状态：**契约已完成（文档层）**；P2—P5 待实施。

### 前置状态说明（对上文状态行的更正，不回写上文）

上文状态行记载"阶段 1 SSH 连接验证已在真实主节点与目标机通过"。这与阶段 1 文档当前的执行记录**不一致**：
阶段 1 的 S4 真实链路验收尚未完成，H1（主机密钥指纹不一致）仍未关闭。因此：

- P1 的前置行（阶段 1 S4）**尚未满足**。P1 只产出契约文档、不写代码，故先行完成；
- **P2 开工前必须由协调者确认 S4 状态或显式豁免**，不能按"阶段 1 已通过"继续；
- 本契约把"该节点 SSH 最近一次测试为 `CONNECTED`"作为分发前置（见下文决策 D14）。
  H1 未解决前真实目标机无法满足该前置，分发会被**刻意阻塞**（fail closed），而不是放宽校验。

### 关键决策

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D1 | 发布包格式版本固定为 `packageFormatVersion = 1`，成员**恰好 6 个**：`manifest.json`、`checksums.txt`、`bin/flowops-executor`、`images/flowops-executor.tar`、`templates/flowops-executor.service`、`templates/docker-compose.yaml` | 固定白名单使校验简单、可审计；成员集变化必须提升格式版本，避免"悄悄多塞一个文件" |
| D2 | 包身份 = 对 `.tar.gz` **文件字节**的 SHA-256（小写十六进制 64 位），同时是存储文件名与远端正式文件名 | 传输校验、幂等、不可变存储共用同一标识；不引入第二套版本号 |
| D3 | 大小上限 **1 GiB**（压缩后），并同时限制成员数 ≤ 64、单成员 ≤ 2 GiB、解压总计 ≤ 4 GiB | 上限必须明确且与传输/存储成本匹配；结构上限防止解压炸弹 |
| D4 | 主节点对上传内容**单遍流式**校验（边读边算摘要、边解析 tar），成员内容不落盘、不执行；只有全部通过才原子改名为正式包 | 满足上文"不解压、不执行包内脚本"的边界；失败不留半成品 |
| D5 | 打包步骤必须**确定性**（固定成员顺序、固定 `mtime`、uid/gid 0、`gzip -n`），同一提交 + 同一镜像 tar + 同一打包工具链重建得到**相同 SHA-256** | "两个运行模式使用同一个经过校验的版本"才有可验证含义；相同摘要重复分发可直接判定成功 |
| D6 | 时间只允许 `manifest.package.sourceDateEpoch`（默认提交时间），**禁止**墙钟时间字段 | 否则同一提交无法重建出相同摘要 |
| D7 | 发布包**同时**支持 `native` 与 `container`，不做"按模式分别打包" | 与阶段 3"同一 runnerId 只有一种安装方式、两种方式共用同一已校验版本"一致 |
| D8 | 分发为**异步**：`POST` 立即返回记录（`PENDING`），前端轮询单条记录；**不提供**取消、暂停与字节级进度 | 1 GiB 传输耗时远超请求超时；v1 用状态机表达进度，避免过度设计 |
| D9 | 同一节点始终串行分发：同摘要进行中返回既有记录（幂等），不同摘要在途返回 409；全局并发上限默认 2 | 避免同一目标机并发写同名临时文件，并限制主节点资源占用 |
| D10 | 目标机目录 `/opt/flowops/runner/packages/` **由 SSH 管理账号预置**，应用不创建、不 `chmod`；目录缺失直接失败 | 沿用上文"远端目录权限由阶段 1 约定的 SSH 管理账号预先配置"的边界，主节点不扩大远端权限 |
| D11 | 连接阶段失败码**与阶段 1 `SshTestResultCode` 同名同义**（`CONNECT_TIMEOUT`、`HOST_KEY_MISMATCH`、`AUTH_FAILED`、`SFTP_FAILED`、`KEY_*` 等），分发特有失败另给 `REMOTE_*`/`UPLOAD_*` 前缀 | 前端可**原样复用**阶段 1 已有的中文映射，不需要第二套映射或前缀转换 |
| D12 | 上传校验失败的文案走 `code=400` + `msg`（不扩展 `Result` 信封、不新增机器码字段） | 与阶段 1 S1 契约一致；前端直接展示 `msg`，避免改动公共响应结构 |
| D13 | 上传容量需把全局 multipart 限制提到 **1280MB**（当前 500MB），使超限由应用返回统一文案 | 否则 1 GiB 包在框架层就被拒绝，错误不可解释 |
| D14 | 分发前置 = 节点已登记 + 已配置 SSH 目标 + 已补齐 `hostKeyAlgorithm` + `lastTest.resultCode == CONNECTED` | "只选已通过阶段 1 SSH 配置的注册节点"可判定；`lastTest` 受阶段 1 R1 版本保护，因此"已通过"永远属于当前设置 |
| D15 | 分发**不重新探测**连接，用已保存设置新建会话，并复用阶段 1 的严格主机密钥（算法 + 指纹）校验与私钥别名边界 | 单一实现、单一信任来源；不在分发路径放宽任何校验 |

### 与阶段 1 SSH 契约的对齐

| 项 | 对齐方式 |
| --- | --- |
| 节点身份 | 沿用 `nexa_node.runner_id`，不引入新标识 |
| 目标机 | 阶段 1 的 `nexa_node_ssh_target`（`host`/`port`/`username`/`keyAlias`/`hostKeyAlgorithm`/`hostKeySha256`），不新增字段、不新增表 |
| 主机密钥 | 分发会话必须执行与 `POST .../ssh/test` 相同的算法 + 指纹双校验；不匹配立即中止且**不写任何远端文件** |
| 私钥 | 只按受限别名从 `flowops.ssh.key-dir` 读取，沿用别名白名单、拒绝符号链接、真实路径边界与 `0600` 校验 |
| 超时 | 连接/认证/SFTP 通道打开复用 `flowops.ssh.*`；传输与远端命令用 `flowops.runner-package.*` 的更长超时 |
| 权限 | 与 `/api/nodes/registry/**` 相同的**仅超级管理员**判定（`NodeAdminGuard`） |
| "已通过配置"的判定 | 用阶段 1 的 `lastTest`（`CONNECTED`）；因 `PUT /ssh` 会清空 `lastTest`，不存在"旧结论覆盖新设置"的窗口 |
| 阶段 1 结果码 | 连接类失败直接复用阶段 1 结果码命名（D11） |
| 不越界 | 不修改 SSH 设置、不改注册 token、不执行 Docker 操作、不提供任意远端命令 |

### 与执行器仓库既有实现的关系（P2 执行方必读）

执行器仓库已有"启动脚本"实现（该仓库 `docs/2026-09-24-unified-startup-script-plan.md`，状态"已实施"）：
`start.sh` + `scripts/start.sh` + `bin/<platform>/flowops-executor`（可选 `docker/start.sh`），并允许按模式分别打包。
这与阶段 2/3 的 SSH 托管分发模型不同（详见 [runner-package-format.md](runner-package-format.md) §9）：

| 差异点 | 既有（2026-09-24 已实施） | 本契约（阶段 2/3） | 处置 |
| --- | --- | --- | --- |
| 二进制路径 | `bin/linux-amd64/flowops-executor` | `bin/flowops-executor` | 本契约固定；既有路径不得用于阶段 2 发布包 |
| 启动方式 | 自带 `start.sh`/`scripts/start.sh` | 主节点渲染的 systemd unit / Compose 项目（阶段 3 O2/O3） | 启动脚本**不进入**发布包；保留在仓库中用于手工/自管部署 |
| 打包粒度 | 可按模式分别打包 | 单个包同时支持两种模式 | 单包双模式（D7） |
| 目录/权限 | 由脚本自行 `chmod` | 主节点受控 SSH 操作，权限由管理账号预置 | 阶段 2 只投递文件，不 `chmod`、不执行 |

要求：阶段 2 发布包格式**以 `runner-package-format.md` 为准**；既有启动脚本文件**保留**、不删除、不改行为；
若 P2 认为包内必须含启动脚本，须**先**由协调者修订格式契约并提升 `packageFormatVersion`，再改 P2。

### 各行的契约输入

- **P2（执行器仓库）**：只读 [runner-package-format.md](runner-package-format.md)（§1–§6、§9 冲突处置）。
- **P3（后端）**：[runner-package-format.md](runner-package-format.md)（§7 校验流程）+ [frontend-api/node-package-api.md](frontend-api/node-package-api.md)（§4–§7）。
- **P4（前端）**：只读 [frontend-api/node-package-api.md](frontend-api/node-package-api.md)（§1–§6），不需要了解包内部格式。

### 分发执行顺序（P3 实现，固定顺序）

1. 前置校验（D14）→ 建记录（`PENDING`）并立即返回。
2. 后台任务：用已保存 SSH 设置建会话（严格主机密钥校验、私钥别名解析，同阶段 1）。
3. 远端预检：`/opt/flowops/runner/packages` 是否存在（`REMOTE_DIR_MISSING`）；已存在 `<sha256>.tar.gz` 时远端计算摘要，一致则 `SUCCEEDED` + `alreadyPresent=true`（跳过传输，处于 `VERIFYING` 阶段）。
4. 空间预检：远端可用空间 ≥ 包大小 + `remote-free-space-margin`，否则 `REMOTE_DISK_INSUFFICIENT`（无法获取远端空间时跳过，依赖写入失败兜底）。
5. 清理旧 `<sha256>.part` → SFTP 流式上传为 `<sha256>.part`（`UPLOADING`，受 `upload-timeout` 限制）。
6. 远端 `sha256sum` 校验 `<sha256>.part`（`VERIFYING`）：不一致 → `REMOTE_CHECKSUM_MISMATCH`，删除临时文件，**保留既有正式包**。
7. 原子改名 `mv -f <sha256>.part <sha256>.tar.gz`，写终态（`SUCCEEDED`）。
8. 任一失败：尽力删除 `<sha256>.part`（清理失败只记服务端日志，不改变记录的 `errorCode`），写 `FAILED` + `errorCode`/`errorMessage`。

远端命令只使用固定模板拼接 64 位十六进制摘要或固定路径，**不接受任何用户输入进入命令**；不解压、不执行包内脚本、不安装、不启动子节点。
主节点在分发中途重启时，未完成记录统一置 `FAILED` + `MASTER_RESTARTED`；目标机遗留的 `<sha256>.part` 由下一次分发覆盖前清理。

### P3 实现要点（由 P1 固定，避免与其它行冲突）

- 迁移文件用**下一个可用版本号**：`V3_1_7__create_nexa_node_package.sql`、`V3_1_8__create_nexa_node_package_distribution.sql`（当前最新为 `V3_1_6`，不得复用已用编号）。
- 表名固定 `nexa_node_package`（主键 `sha256 CHAR(64)`）与 `nexa_node_package_distribution`（自增 `id`，`runner_id VARCHAR(63)`，外键 `nexa_node.runner_id ON DELETE CASCADE`，沿用 `V3_1_4` 写法）。
- 上传容量需同步把 `spring.servlet.multipart.max-file-size` / `max-request-size` / `server.tomcat.max-swallow-size` 提到 1280MB（见 API 契约 §7）。
- 分发前置、状态机、`errorCode` 取值与中文文案必须与 API 契约 §4–§5 完全一致；前端按此实现，不得各自新增取值。

### 前置与未决

| 项 | 状态 |
| --- | --- |
| P1 前置行"阶段 1 S4" | **未满足**：S4 未验收，H1（主机密钥指纹不一致）未关闭。P1 作为纯文档行先行定稿 |
| P2 开工条件 | 需协调者确认 S4 状态，或书面豁免（豁免不影响本契约，只影响真实链路风险的接受方） |
| 主节点固定目录预置 | 需运维在目标机执行 `install -d -m 755 /opt/flowops/runner/packages`（D10）；主节点不负责创建或改权限 |
| `packageFormatVersion` 提升规则 | 见格式契约 §10；P2 不得单方面扩成员或改路径 |

## 2026-09-28 配置构建与一键安装修订（仅追加，不改上文原始记录）

本节承接[执行器配置统一与节点一键安装修订计划](2026-09-28-runner-configuration-and-one-click-install-plan.md)，是后续决策记录；上文保留为原始 P1/P2 记录，若与本节冲突，以本节及[发布包格式契约的修订条款](runner-package-format.md#11-2026-09-28-配置统一修订)为准。当前代码仍采用两套配置文件和双环境 overlay，修订**尚未实施**。

| 修订点 | 定稿内容 | 对原计划的影响 |
| --- | --- | --- |
| P2 配置来源 | 每环境只维护 `config/profiles/{prod,dev}.yaml` 一份受版本控制的非敏感默认值；发布脚本调用同一个 Go 生成器，不再维护 `deploy/release-config/**` 副本 | 替代上文发布配置副本的说明；既有 `config/config.{prod,dev}.yaml` 本机私有文件保留为外部覆盖，不进入包 |
| 构建环境 | `release.sh --env` / `release.ps1 -Environment`，缺省 prod；仅所选环境进入二进制及镜像，运行环境不匹配时启动失败；dev 包版本使用 `-dev` 后缀 | 包仍是同一 Linux amd64 六成员格式 v1，一个包同时支持 native/container；不新增 tar 成员或 manifest 字段 |
| 凭据交付 | `runnerId`、主节点可达地址、token 不在 P2/P3 通用包内；阶段 3 安装作业按节点经已核验 SSH/SFTP 写目标机专属 `0600` YAML | P3 仍只分发不可变包，不写配置、不启动；“一键分发并安装”由阶段 3 后端作业串行协调两个阶段 |
| token 来源 | 首次托管安装由主节点生成随机 token 并更新 `nexa_node` 哈希；升级/重启沿用目标机凭据 | 替代阶段 3 原“安装时管理员再次输入当前 token”的设计，不改变 P3 的分发接口 |

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P2-C | `D:\project\go\flowops-executor` | 按新契约收敛配置源、实现打包环境选择与非敏感内嵌配置生成 | P1、修订计划 C0 | `config/**`、`deploy/**`、`README.md`、对应测试 | 单源配置、prod 默认/dev 显式的无密钥六成员包 | 空配置拒绝启动、敏感值扫描、环境错配拒绝、版本/摘要一致；有 Docker 的机器验证镜像 | 不改 P3 API、不写目标机运行配置 |
| P3-C | `D:\project\backend\flowops`（协调者） | 核对 P3 上传/分发验证仍接受符合 v1 契约的 prod/dev 包，保持包与目标机凭据分离 | P2-C、P3 | P3 包校验模块、本文档验收记录 | 与环境选择兼容但不接收节点凭据的分发链路 | prod/dev 包独立摘要，六成员校验；分发后目标机无凭据文件和新进程 | 不在阶段 2 提前安装、启动或轮换 token |

P2-C 与 P3-C 的完成不代表一键安装已完成；对应实现与真实验收见修订计划 C2—C5。阶段 1 S4 与 P5 的真实链路证据仍需单独补齐。

## 2026-09-28 P2-C 实施记录（仅追加，不改上文原始记录）

上节末"修订尚未实施"的状态已失效：P2-C（等同修订计划 C1）已在执行器仓库实现并通过本地验证。
实现细节与判定标准见执行器仓库 `D:\project\go\flowops-executor\deploy\README.md`（P2 / P2-C 交付说明）。

落地内容：

| 项 | 落地情况 |
| --- | --- |
| 单源配置 | 唯一受版本控制源 `config/profiles/{prod,dev}.yaml`（仅非敏感默认值）；`deploy/release-config/**` 已删除 |
| 共享生成器 | `deploy/packager genconfig --env <prod\|dev> --version <v> --out <file>`，白名单校验（字段名 + 取值类型），凭据与 `database` 段一律失败 |
| 内嵌配置 | `config/embedded.yaml` 单文件 `go:embed`（prod profile 生成结果入库，`0.0.0-local`）；`LoadEmbedded()` 不再按环境选文件，`ErrEmbeddedNotFound` 已移除 |
| 环境选择 | `release.sh --env` / `release.ps1 -Environment`，缺省 prod；dev 版本强制 `-dev` 后缀（自动补），prod 拒绝 `-dev` |
| 环境隔离 | `go build -overlay` 仅替换内嵌配置（工作区零副作用），只编入所选环境；`-ldflags -X config.BuildEnvironment` 与运行时 `APP_ENV` 不一致即拒绝启动 |
| 凭据交付 | `runner.id` / `master_addr` / `token` / `database` 不入二进制与归档，由阶段 3 写入目标机专属 `0600` YAML；本机私有 `config/config.{prod,dev}.yaml` 保持不入库，仅作外部覆盖 |

验证证据（2026-09-28，Windows + WSL2，构建机**无任何容器工具**）：

| 验收项（P2-C 行） | 结果 |
| --- | --- |
| 空配置拒绝启动 | ✅ 清空 `APP_ENV`/凭据/`FLOWOPS_CONFIG` 后运行 prod 二进制，exit 1：`配置校验失败…均为空: runner.id, runner.master_addr, runner.token`，未尝试连接主节点 |
| 敏感值扫描 | ✅ 构建脚本自动检索 6 项私有取值，均未出现在二进制中 |
| 环境错配拒绝 | ✅ 双向：prod 二进制 + `APP_ENV=dev`、dev 二进制 + `APP_ENV=prod` 均 exit 1（`运行环境不匹配：本二进制为 prod 环境构建…`） |
| 仅所选环境编入 | ✅ 字节检索：prod 构建含 `level: info` 不含 `level: debug`，dev 构建反之 |
| 版本规则 | ✅ dev 自动补 `-dev`；`--env prod --version 0.7.0-dev` 被拒绝 |
| 六成员与摘要一致 | ✅ 占位镜像演练：`verify` 通过（6 成员、权限/mtime/checksums/manifest），同载荷重复打包 SHA-256 相同（`4b9a29f7…`） |
| 单测 | ✅ `go test ./...` 全通过（含 `config` 多来源与环境一致性、`deploy/packager` 打包/校验/生成器/防漂移） |

未验证项（需在**有 Docker** 的机器上补，命令见 `deploy/README.md` §6）：镜像构建与镜像内
`docker --version` / `docker compose version` 采集、含真实镜像包的完整构建与重复构建比对。
故本行按"无密钥六成员包 + 本地可验证项"记完成，镜像环节仍待 P3-C/C5 侧补证。


## 2026-09-28 P3 与 P3-C 实施记录（仅追加，不改上文原始记录）

本节记录阶段 2 **P3**（后端包接收/校验/存储/分发，`D:\project\backend\flowops`）与 **P3-C**（prod/dev 包兼容与凭据分离核对，协调者行）
的落地内容与验证证据。上文原始内容与历史修订均未改动。

### 结论与边界

| 项 | 状态 |
| --- | --- |
| P3 代码（上传/校验/不可变存储/分发/记录/清理/迁移/配置/测试） | **已实现**，`flowops-app` 全量测试 209 项通过（0 失败；2 项跳过＝`NodeSshServiceTest` 里需 POSIX 的符号链接用例，属既有跳过） |
| P3-C prod/dev 兼容 + 凭据分离核对 | **已按本地可验证项完成**（真实 Go 侧包固件 + 真实进程内 SSH/SFTP 链路） |
| P3 真实链路验收（P5） | **未做**：没有真实 Linux 目标机与真实 MySQL，见文末"未验证" |
| P3-C 对应的一键安装（C3—C5） | **未做**：属阶段 3 行，本行不提前安装/启动/轮换 token |

### 交付物

| 类型 | 文件 |
| --- | --- |
| 迁移 | `sql/migration/V3_1_7__create_nexa_node_package.sql`、`V3_1_8__create_nexa_node_package_distribution.sql` |
| 实体/Mapper | `entity/NexaNodePackage.java`、`entity/NexaNodePackageDistribution.java`、`mapper/NexaNodePackageMapper.java`、`mapper/NexaNodePackageDistributionMapper.java` |
| 包模块（新增包 `com.nexa.flowops.service.nodepackage`） | `RunnerPackageValidator`、`RunnerPackageManifest`、`RunnerPackageInspection`、`RunnerPackageFailure`、`RunnerPackageException`、`PackageDigests`、`RunnerPackageSettings`、`LocalRunnerPackageStore`、`RunnerPackageRegistry`、`RunnerPackageService`、`PackageDistributionStatus`、`PackageDistributionErrorCode`、`PackageDistributionFailure`、`PackageDistributionRequestException`、`RunnerPackageSshClient`、`RunnerPackageDistributor`、`RunnerPackageDistributionService` |
| 接口 | `controller/NodePackageController.java`（6 个接口，全部仅超管） |
| DTO | `dto/RunnerPackageVO.java`、`dto/PackageDistributionVO.java` |
| 配置 | `application.yml`：`spring.servlet.multipart.max-file-size` / `max-request-size` / `server.tomcat.max-swallow-size` 由 500MB 提升为 1280MB（框架上限高于 1 GiB 应用上限，超限由应用返回固定文案） |
| 测试（新增 77 项） | `RunnerPackageValidatorTest`(26)、`RunnerPackageUploadServiceTest`(9)、`RunnerPackageRealFixtureTest`(4)、`RunnerPackageDistributionLinkTest`(7)、`RunnerPackageDistributionServiceTest`(17)、`RunnerPackageDistributorFailureTest`(2)、`RunnerPackageSshConsistencyTest`(6)、`NodePackageControllerTest`(6)，以及 `RunnerPackageTestFixture` 与 `src/test/resources/runner-package/` |
| 文档 | 本记录；[发布包格式契约 §12](runner-package-format.md#12-2026-09-28-p3-校验实现修订) |

### 验证证据（2026-09-28，Windows，Java 17，`./mvnw.cmd test`）

| 验收项（P3 行 / P3-C 行） | 结果 |
| --- | --- |
| 正确包（真实 Go 产出，prod 与 dev） | ✅ `RunnerPackageRealFixtureTest`：Java 校验器接受 `packager` 产出的两个包，摘要与 Go 侧 `verify` 完全一致（prod `02c16ee9800cc57c0a0dd8693747debc8705d274508d96ff0067ff7e818376ab`、dev `6b915df70c8b855d382f1faf4e9a02e4fb09991340ecb8c4731806294f7c9230`），六成员集合一致 |
| prod/dev 独立摘要 | ✅ 两包摘要不同、各自入库（`sha256` 为唯一键），互不覆盖 |
| 篡改包被拒 | ✅ 26 项校验用例：文件名/版本/平台/格式版本、未知字段、白名单外成员、缺失/重复成员、权限/uid/uname/mtime 不符、非普通文件（符号链接）、路径穿越、checksums 摘要不符/CRLF/未升序/行数不符、超包大小、超成员大小、非 gzip、空包 |
| 六成员校验 | ✅ 成员白名单 + `manifest.members`（4 载荷）与归档交叉核对，成员数上限 64、单成员 ≤2 GiB、解压总计 ≤4 GiB |
| 重传（同摘要） | ✅ 上传幂等（`existing=true`、不重复落盘、不改既有文件）；分发在途同摘要返回既有记录；目标机已有同摘要且远端摘要一致 → `SUCCEEDED` + `alreadyPresent=true`（跳过传输） |
| 连接中断 / 传输超时 | ✅ `RunnerPackageDistributorFailureTest`：受控 SFTP 覆盖 `UPLOAD_TIMEOUT`（看门狗中断会话）、`UPLOAD_INTERRUPTED`（会话已断开）、`REMOTE_DIR_NOT_WRITABLE`（权限拒绝）、`REMOTE_WRITE_FAILED` |
| 远端磁盘不足 | ✅ `RunnerPackageDistributionLinkTest`：`df` 报告 1 KiB 可用 → `REMOTE_DISK_INSUFFICIENT`，且发生在传输与校验之前，目标机不落任何文件 |
| 目标机目录缺失 | ✅ `REMOTE_DIR_MISSING`，主节点不创建远端目录 |
| 远端缺基础命令 | ✅ `sha256sum` 退出码 127 → `REMOTE_TOOL_MISSING`，临时文件被清理 |
| 远端摘要不符 | ✅ `REMOTE_CHECKSUM_MISMATCH`，不改名为正式包、清理 `.part`、**保留目标机既有正式包**（`failureKeepsExistingPackageUntouched`） |
| 普通用户 403 | ✅ `NodePackageControllerTest`：六个接口在非超管下均返回 `code=403` 且不触达服务层 |
| 分发状态机与错误码落库 | ✅ `RunnerPackageDistributionServiceTest`：`PENDING` 返回 → 阶段只能 `UPLOADING → VERIFYING` 单调前进 → 终态 `SUCCEEDED`/`FAILED`；连接阶段错误码沿用阶段 1（如 `HOST_KEY_MISMATCH`），远端阶段用 `REMOTE_*`，未预期异常只落 `INTERNAL_ERROR` 且不泄漏异常文本 |
| 并发与前置 | ✅ 同节点其它摘要在途 → 409；队列饱和 → 409；未登记节点 404；未配 SSH/未测通过/缺算法 → 400；分发只读取已保存设置，不重新探测 |
| 重启恢复 | ✅ `recoverInterrupted()` 把在途记录置 `FAILED(MASTER_RESTARTED)` 并清理上传临时文件（`ApplicationReadyEvent`，失败只记日志） |
| **P3-C：包与节点凭据分离** | ✅ 夹带凭据的包被拒（第 7 个成员 `config/runner-private.yaml` → `MEMBER_INVALID`；manifest 追加 `runner{id,token}` → `MANIFEST_INVALID`）；真实分发链路上目标机**只多出** `/opt/flowops/runner/packages/<sha256>.tar.gz`，没有 env/config/私钥文件，没有解包目录 |
| **P3-C：不启动任何进程** | ✅ 真实链路记录到的远端命令全部属于 `{sha256sum -- , df -Pk -- , mv -f -- }` 白名单（清理走 SFTP `remove`），断言不含 `systemctl`/`docker`/执行器二进制/`token`/`private`/`.env` |
| **P3-C：与阶段 1 判定一致** | ✅ `RunnerPackageSshConsistencyTest`：同一目标机/设置/私钥下，`NodeSshService.test` 与分发会话对 `CONNECTED`、`HOST_KEY_MISMATCH`、`HOST_KEY_ALGORITHM_UNAVAILABLE`、`KEY_NOT_FOUND`、`KEY_ALIAS_INVALID`、`HOST_KEY_ALGORITHM_REQUIRED` 给出同一判定 |
| 部署接线 | ✅ `FlowopsApplicationTests`（Spring 上下文）通过：新增 Bean、`flowops.runner-package.*` 默认值与 `ApplicationReadyEvent` 恢复逻辑不影响启动 |

### 实现中的契约细化（已同步到格式契约 §12）

| 编号 | 细化 | 原因 |
| --- | --- | --- |
| P3-1 | 上传校验改为**两段式本地流水线**：先把上传流式写入临时文件并同步算 SHA-256，再从该临时文件解析 gzip/tar | 契约 §7 原写"单遍 tee 流水线"；实测 `GzipCompressorInputStream` 会 `mark/reset` 复读底层流，tee 会把缓冲区重复写盘（1008 B 的上传写成 2006 B，摘要与内容不符）。改为两段式后摘要严格等于落盘字节，内存仍然有界，失败仍不留半成品 |
| P3-2 | `manifest.json` **拒绝未知字段**（原 §3 写"忽略未知字段"） | P3-C 要求包与节点凭据分离，未知字段是夹带凭据的通道；格式 v1 字段集固定，新增字段按 §10 必须先提升 `packageFormatVersion`，因此拒绝不损害前向兼容 |
| P3-3 | 主节点额外强制 `uid/gid=0`、`uname/gname` 为空、成员 `mtime == package.sourceDateEpoch`、成员权限白名单 | 与执行器仓库 `deploy/packager verify` 的判定对齐（原契约只把这些写成构建要求） |
| P3-4 | 结构性文本成员（`manifest.json`、`checksums.txt`）各限 256 KiB | 内存有界的实现约束；远高于真实取值（约 1 KB） |
| P3-5 | 远端临时文件清理用 SFTP `remove`，**不执行** `rm` 命令；远端命令实际只用 `sha256sum --`、`df -Pk --`（空间预检，不可用时跳过）、`mv -f --` | 少一个远端工具依赖；`REMOTE_TOOL_MISSING` 文案已相应改为 `sha256sum / mv` |
| P3-6 | 分发并发＝进程内有界线程池（默认 2 线程、队列 32，可用 `flowops.runner-package.*` 调整）＋"同节点在途记录"串行判定 | 落实契约 §4.2 的幂等/并发语义；重启后在途记录统一置失败，锁自然释放 |
| P3-7 | 上传判定的包身份＝**落盘字节**的 SHA-256，且返回值与存储文件名、后续分发源完全一致 | 摘要即身份，避免"上传校验的摘要"与"实际存储内容"出现偏差 |

### 未验证（不得据此声称阶段 2 完成）

- **真实 Linux 目标机、真实 SSH/SFTP 链路**：本行验证用的是进程内 Apache MINA SSHD（真实 SSH 协议与 SFTP 子系统）+ 受控远端命令；
  P5/C5 仍需在真实主机上核对远端摘要、正式文件路径与权限。
- **真实 MySQL 迁移执行**：`V3_1_7`、`V3_1_8` 仅由本行新增，未在目标库执行；仅有迁移文件不等于库已更新（沿用阶段 1 R4 的口径）。
- **1 GiB 级真实大包**：大小上限与限长分支有测试（缩小上限触发），但未跑过一次真实 1 GiB 传输与磁盘余量场景。
- **阶段 1 S4 前置仍未满足**：S4 未验收、H1 未关闭，因此分发前置 `lastTest == CONNECTED` 在真实环境仍会被阻塞；这是刻意 fail closed。
- **P4 页面、阶段 3 安装/启动、C3—C5**：均未实现，本行不写目标机配置、不安装、不启动、不轮换 token。
- **镜像环节**：真实镜像 tar 与镜像内 `docker compose version` 依赖有 Docker 的机器（P2-C 遗留），本行沿用同一限制。
