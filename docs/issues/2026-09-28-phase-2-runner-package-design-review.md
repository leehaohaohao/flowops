# 待修复：阶段 2 发布包与 SSH 分发设计审阅

> 状态：**待修复，真实验收未完成**。阶段 2 的构建、上传、分发和页面代码已存在；[阶段 2 计划](../2026-09-27-runner-package-distribution-plan.md)中的 P5 真实 Linux/MySQL/SSH 验收及[阶段 1 SSH 计划](../2026-09-27-node-ssh-connection-plan.md)的 H1/S4 仍须单独核对。本文是审阅与修复计划，不代表下列改动已经实施。阶段 3 的模板消费防护明确排在阶段 3 开发，不混入阶段 2 的分发职责。

## 审阅结论与问题清单

| 编号 / 优先级 | 现有实现与触发条件 | 影响 | 确定的修复方案 |
| --- | --- | --- | --- |
| R1 / 高 | `RunnerPackageDistributionService.distribute()` 先检查 SSH 目标的 `lastResultCode == CONNECTED`，排队后 `runDistribution()` 再从库中读取目标。两次读取之间若管理员修改 SSH 设置，任务可使用与已通过测试不同的目标；现有 `configVersion` 未参与分发判定。 | “仅向已验证目标分发”的前置失效；即使连接时仍严格核验配置中的算法和指纹，也不能证明**新配置**曾经过连接测试。 | 建记录时保存已验证的 SSH `configVersion`（必要时连同目标关键字段的不可变快照）；后台连接前重新核对版本及 `CONNECTED` 属于该版本，变化则以固定错误码失败且不写远端。连接过程使用核对后的同一快照，不再另取一份可变目标。SSH 设置在传输期间变化时，本次会话只作用于原快照目标，并在记录中明确关联版本；阶段 3 安装前须再次核对当前版本。 |
| R2 / 中 | `RunnerPackageService.upload()` 遇到数据库已有相同摘要，直接删除新临时文件并返回 `existing=true`；`LocalRunnerPackageStore.commit()` 对同名正式文件只按存在性处理。数据库行与卷中文件缺失、损坏或曾经落盘但登记失败时会失同步。 | 重传显示成功却无法分发；仅凭文件名“不可变”不能证明磁盘内容仍等于摘要。 | 在返回已存在或复用正式文件前核对文件大小与 SHA-256。数据库有记录但文件缺失/损坏时，以本次已校验上传文件原子修复；文件存在而数据库缺记录时核对内容后补登记。文件提交与数据库登记失败的恢复语义、并发同摘要上传和异常清理写入测试。分发读源文件时仍以期望摘要校验最终结果，不让错误文件被当作成功。 |
| R3 / 中 | Go `BuildPackage()` 用 `os.ReadFile()` 读取整个镜像 tar，`VerifyPackage()` 用 `io.ReadAll()` 读每个成员；构建端没有对成品执行后端的压缩包 **1 GiB** 上限。 | 真实镜像接近上限时构建/校验可能耗尽内存；构建器可能报告成功，却产出后端必拒绝的包。 | 保留格式 v1 六成员、成员顺序和可重复摘要，改为按文件流式计算成员摘要、流式写 tar/gzip、流式校验大成员；只在内存中保留有界的 manifest/checksums。构建与 `verify` 都强制压缩包、单成员和解压总量上限，超限删除未完成输出并给明确错误。用受控的大文件样本验证内存有界和 Go/Java 双端一致。 |
| R4 / 阶段 3 前阻断 | 当前包内 systemd/Compose 模板仍读取 release 目录下的 `executor.env`，而[配置统一与一键安装计划](../2026-09-28-runner-configuration-and-one-click-install-plan.md)已定稿节点专属 `0600` YAML。已上传的包是不可变的；今后改模板不会改掉旧包。现有上传校验只核对模板成员的存在与摘要，不核对其消费凭据的语义。 | 若阶段 3 直接安装旧包，可能继续把 token 放入环境文件/容器环境，违反已定稿的凭据边界。 | 阶段 3 O1 先定稿**模板兼容判定**：native 通过 `--config` 读取私有 YAML，container 只读挂载并以 `FLOWOPS_CONFIG` 指向它，禁止 `EnvironmentFile`/`env_file` 传 token。执行器仓库 O2 更新两个模板和校验；主节点 O3 对**包含已入库旧包在内**的安装候选检查该能力，不兼容则拒绝安装并给明确错误。阶段 2 的现有分发 API 仍只搬运不可变包，不执行安装。若要修改 manifest 字段或成员集合，协调者先按[包格式契约](../runner-package-format.md)的变更规则升级格式版本；不得单仓静默改契约。 |

R1、R2、R3 是阶段 2 代码修复项，需在 P5 真实分发验收前完成。R4 是阶段 3 消费边界，必须在 O3 安装真实包前完成；它不阻止阶段 2 将旧包分发到目标机，但旧包不得因此被判定为“可安装”。本次审阅没有发现需要修改 `nexa-protocol` 的问题。

## 跨仓契约与所有权

协调者在 `D:\project\backend\flowops` 定稿以下契约，再启动依赖实现：R1 的配置版本与失败码、R2 的重传修复语义、R3 的 Go/Java 一致大小上限、R4 的阶段 3 模板兼容规则和拒绝响应。分发记录 API 如新增版本字段或失败码，先更新 `docs/frontend-api/node-package-api.md`，前端再消费；包格式若变更，先更新 `docs/runner-package-format.md`。各实现者只改本行仓库和允许范围，跨仓需求交协调者重排。

| 仓库绝对路径 | 所有权与边界 |
| --- | --- |
| `D:\project\backend\flowops` | 协调者定稿契约；后端修复 SSH 版本绑定、包文件/数据库一致性；阶段 3 再实现安装前模板兼容门禁；记录 P5 验收 |
| `D:\project\go\flowops-executor` | 流式构建/校验与限制；阶段 3 O2 再更新 systemd/Compose 模板及校验 |
| `D:\project\front\flowops-front` | 核对现有 P4 页面和新错误码展示，完成构建与页面测试；不接触包内部或节点凭据 |
| `D:\project\mix\nexa-protocol` | 本项无修改 |

## 开发顺序表

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D0 | `D:\project\backend\flowops`（协调者） | 定稿 R1—R4 的跨仓契约，明确配置版本失效码、同摘要修复规则、大小上限与阶段 3 模板兼容判定；更新 API/包格式文档中受影响的条款 | 无 | 本文件、`docs/frontend-api/node-package-api.md`、`docs/runner-package-format.md`、阶段 2/3 计划；代码只读 | 后端、Go、前端共同使用的确定性契约 | 对照现有 `configVersion`、包格式 v1、P5/O1—O3 边界和既有错误码 | 不修改业务代码，不提前实现阶段 3 |
| B1 | `D:\project\backend\flowops` | 将分发记录绑定已验证的 SSH 配置版本和目标快照；后台执行前核对，失效时不建远端会话并落固定失败码（R1） | D0 | `flowops-app` 的 `service/nodepackage/**`、分发记录实体/mapper/迁移、相关测试；必要时 `docs/frontend-api/node-package-api.md` | 已排队任务不会使用未经测试的新 SSH 设置 | 控制式并发测试：排队后修改 host/port/指纹/密钥别名，任务失败且远端无写入；不变配置正常分发 | 不改 SSH 测试本身的主机密钥策略，不修改 Go/前端 |
| B2 | `D:\project\backend\flowops` | 修复数据库索引与本地正式包文件失同步的重传/并发恢复语义（R2） | D0 | `flowops-app` 的 `service/nodepackage/**`、包索引测试；仅在确需数据字段时涉及包表迁移 | 同摘要重传能修复缺失/损坏文件，健康文件仍不可变 | 数据库有行但文件缺失/损坏、文件有而数据库无行、登记失败、并发相同摘要上传；分发源摘要一致 | 不覆盖健康包，不修改远端已分发包，不执行安装 |
| G1 | `D:\project\go\flowops-executor` | 将构建与校验改为流式处理并在两处执行格式 v1 的大小上限（R3） | D0 | `deploy/packager/**`、`deploy/release.sh`、`deploy/release.ps1`、对应测试和 `deploy/README.md` | 有界内存、可重复构建且后端可接收的六成员包 | 大镜像模拟、超限拒绝及清理、两次构建摘要一致、Go `verify` 与 Java 校验器接受同一合法包；有 Docker 的机器补真实镜像验证 | 不改 `runner/**` 行为，不改包成员集合、主节点或协议 |
| F1 | `D:\project\front\flowops-front` | 复核 P4 上传/分发/轮询界面对 B1 新失败码及 P5 场景的展示，运行专项页面测试和生产构建 | D0、B1 | `src/pages/NodeList.tsx`、`src/api/nodes.ts`、`src/types/**`、相关测试 | 页面能区分“SSH 设置已改变，需重测”与传输失败 | 有权限/无权限、错误码、失败重试与状态轮询；构建退出码 0 | 不新增安装/启动按钮，不保存 token，不修改后端 |
| V1 | `D:\project\backend\flowops`（协调者验收） | 完成阶段 2 P5：从实际主节点上传真实镜像包，经已验证的 SSH 目标分发到 Linux，补 MySQL 迁移与中断重试证据；更新阶段 2 文档中 P4/P5 的真实状态 | B1、B2、G1、F1；阶段 1 H1/S4 完成 | 阶段 2 计划、本文件验收记录；真实环境操作；发现缺陷回流对应行 | 可复核的阶段 2 真实验收记录 | 包/远端摘要一致、旧包保留、失败无正式半包、无新 runner 进程、真实体量与磁盘余量；页面状态一致 | 不以进程内 SSH 或模拟 MySQL 代替真实验收，不宣称阶段 3 已完成 |
| G2 | `D:\project\go\flowops-executor`（阶段 3 O2） | 按 D0 的兼容规则更新 systemd/Compose 模板及包校验，使新包只读取节点专属 YAML（R4） | D0、阶段 3 O1 契约定稿 | `deploy/templates/**`、`deploy/packager/**`、模板测试与说明 | 无 `executor.env` 凭据注入的新包 | native `--config`、container 只读挂载/`FLOWOPS_CONFIG`、`docker inspect` 不含 token；旧模板明确判不兼容 | 不生成/分发 token，不改阶段 2 分发业务逻辑 |
| B3 | `D:\project\backend\flowops`（阶段 3 O3） | 安装前对所有候选包（含阶段 2 已入库旧包）执行 R4 模板兼容门禁，不兼容直接拒绝 | D0、G2、阶段 3 O1 契约定稿 | `flowops-app` 阶段 3 安装模块、包内容读取/校验模块、相关测试与安装 API 文档 | 旧包可保留分发记录，但不能被误当作可安全安装 | 旧 `executor.env` 模板拒绝、新 YAML 模板通过；拒绝发生在 token 轮换或远端写入前 | 不回写不可变包，不在阶段 2 分发 API 中安装/启动 |
| V2 | `D:\project\backend\flowops`（协调者） | 在阶段 3 的真实安装验收中复核 R4：旧包拒绝、新包双模式安全读取凭据 | B3、阶段 3 其余 O3/O4 实现 | 本文件、阶段 3 验收记录；真实环境操作 | R4 关闭证据 | native/container 均上线，包与 `docker inspect`/日志无 token，旧包拒绝且不轮换凭据 | 不以模板单测代替真实安装，不回头改变阶段 2 已完成的分发记录 |

阶段 2 P5 与阶段 3 V2 是两道不同的验收门。D0、B1、B2、G1、F1 可在真实 SSH 联调推进期间完成；V1 必须等待 H1/S4。G2/B3/V2 明确属于后续阶段 3，不能由阶段 2 实现者提前越界完成。协调者最终按本表、当前代码、测试及实际环境证据关闭问题。

## 本次审阅的验证边界

2026-09-28 本机复核：后端阶段 2 定向测试 **77 项通过**；Go `deploy/packager` 与 `config` 测试通过；前端 `tsc -b` 通过。前端完整 `npm run build` 在当前执行环境因 `esbuild` 子进程 `spawn EPERM` 未能完成，不能据此判断页面构建成功或失败。真实镜像、1 GiB 量级、目标 Linux、生产 MySQL 迁移和 SSH/SFTP P5 尚无本次验收证据。[阶段 2 计划](../2026-09-27-runner-package-distribution-plan.md)旧记录仍写“P4 页面未实现”；当前前端已有页面代码，V1 应按代码、测试和真实页面结果更新状态，不改写历史记录的原始内容。

## D0 契约定稿（2026-09-28，仅追加）

> 本节是 [开发顺序表](#开发顺序表) 中 **D0** 行的交付物：R1—R4 的跨仓契约定稿。本节只改文档，
> **未修改任何业务代码**；B1／B2／G1／F1／V1 的实现与验收状态见各行的执行记录。
> 上文审阅结论与问题清单保持原样；本节对其中"确定的修复方案"做可实现的细化，冲突时以本节为准。
>
> 同步修订：[`docs/frontend-api/node-package-api.md`](../frontend-api/node-package-api.md) §8（新增字段与失败码）、
> [`docs/runner-package-format.md`](../runner-package-format.md) §13（双端大小上限与阶段 3 模板兼容判定）。

### 0. 本次定稿只做三件事

1. 把 R1 的"版本绑定"从意图变成**字段、时序、失败码**都可判定的规则；
2. 把 R2 的"失同步修复"变成**分支可枚举、结果可断言**的规则，并明确分发前的本地源校验；
3. 把 R3 的"双端一致大小上限"与 R4 的"阶段 3 模板兼容判定"变成**唯一常量表 + 可机械检查的标记清单**。

包格式仍为 `packageFormatVersion = 1`：**不新增/删除归档成员，不新增 manifest 字段**，`nexa-protocol` 不修改。

### 1. R1 契约：分发绑定已验证的 SSH 配置版本

**语义**：分发任务在**建记录时**绑定"当时已通过连接测试的那一版 SSH 设置"，后台执行前必须再次证明这一版仍是当前版本，
否则以固定失败码终止，**不建立远端会话、不写任何远端文件**。

| 项 | 定稿规则 |
| --- | --- |
| 绑定来源 | 阶段 1 的 `nexa_node_ssh_target.config_version`（`V3_1_5` 已存在，`BIGINT NOT NULL DEFAULT 0`，每次 `PUT /ssh` 覆盖设置时 +1 并清空 `latest_test_seq` 与旧 `lastTest`） |
| 记录字段 | `nexa_node_package_distribution.ssh_config_version BIGINT NULL`；`NULL` 表示未绑定（历史记录或绑定前创建），分发时视为失效。迁移用下一个可用版本号：`V3_1_9__add_distribution_ssh_config_version.sql` |
| 建记录前置（请求级，400/404） | 现有四条前置不变（节点已登记、SSH 目标存在、`hostKeyAlgorithm` 已补齐、`lastTest.resultCode == CONNECTED`），并在此刻把当前 `config_version` 写入 `ssh_config_version` |
| 后台执行前提（运行期） | 任务开始时**只读一次** SSH 目标，然后按顺序判定：① 记录存在且 `ssh_config_version != NULL`；② 该次读取的 `config_version == ssh_config_version`；③ 该次读取的 `last_result_code == CONNECTED`。任一不满足 → `FAILED`，不建会话 |
| 会话用哪份目标 | **就是这一次读取到的对象**（不再二次读取）：即使传输期间管理员改了设置，本次会话只作用于已核对的那一版设置。目标行被删除同样命中失效分支 |
| 失效失败码 | 版本不一致或目标行缺失 → `SSH_CONFIG_CHANGED`；版本一致但最近一次测试不是 `CONNECTED` → `SSH_NOT_VERIFIED`。二者都在建会话之前产生，`error_message` 用固定文案，且**不产生任何远端写入** |
| 记录展示 | `PackageDistributionVO` 新增 `sshConfigVersion`（number\|null），前端可展示"绑定版本"，便于判断"设置已变更需重测" |
| 阶段 3 边界 | 安装（O3/B3）在自己的作业开始时**必须再核对一次当前 `config_version` 与 `CONNECTED`**，不得复用阶段 2 记录里的绑定结论；阶段 2 不实现这一步 |
| 不做什么 | 不放宽阶段 1 的主机密钥策略（算法 + 指纹双校验不变）；不因版本变化自动重测；不静默回退到"当前设置" |

> 为什么版本相等就足够：`PUT /ssh` 会同时清空 `lastTest`，因此"`CONNECTED` + 版本相等"必然意味着那次成功的连接测试属于**这一版**设置（阶段 1 R1 的结果保护）。

### 2. R2 契约：索引与本地正式包文件的一致性

**语义**：包的**身份是摘要**，而"磁盘内容仍等于摘要"必须每次都能被证明；数据库行与卷文件任一侧缺失/损坏都要能被下一次上传自愈，且**健康的包永不被改写**。

上传时按下列分支处理（`existing` 表示"索引行此前已存在"，新增 `repaired` 表示"本次请求重写了磁盘文件"）：

| 分支 | 触发条件 | 行为 | 响应 |
| --- | --- | --- | --- |
| 全新 | 无索引行、无同名文件 | 原子改名为 `<sha256>.tar.gz`，插入索引 | `existing=false`、`repaired=false`，`msg="发布包已上传"` |
| 健康复用 | 有索引行，且磁盘文件大小与 SHA-256 均等于摘要 | **不动文件**，直接返回 | `existing=true`、`repaired=false`，`msg="发布包已存在（相同摘要）"` |
| 修复文件 | 有索引行，但文件缺失或内容/大小与摘要不符 | 用本次已校验的上传内容走"临时文件 + 原子改名"覆盖，**不原地截断** | `existing=true`、`repaired=true`，`msg="发布包已存在，已按摘要修复存储文件"` |
| 补登记 | 无索引行，但同名文件已存在且**内容复核等于摘要** | 复用该文件，插入索引行 | `existing=false`、`repaired=false`，`msg="发布包已补登记（文件已存在且摘要一致）"` |
| 内容复核失败 | 无索引行且同名文件内容不等于摘要 | 视为损坏文件，按"全新"分支原子替换并登记 | `existing=false`、`repaired=true`，`msg="发布包已上传"` |

补充规则：

- **分发前的本地源校验**（新增，避免把损坏源文件当成传输失败）：后台任务在**建立 SSH 会话之前**核对源文件大小与 SHA-256；
  不符 → `FAILED` + 新失败码 `STORE_CHECKSUM_MISMATCH`（文案「主节点存储的发布包与摘要不一致，请重新上传该包」），不建会话、不传输。
- 传输完成后仍保留远端摘要核对（`REMOTE_CHECKSUM_MISMATCH`），用于发现读取期间的存储变化与传输损坏。
- **并发同摘要上传**：允许多个请求同时校验；文件提交只走"同目录临时文件 + 原子改名"，目标已存在且健康时保留既有文件；
  索引以 `sha256` 为主键，插入冲突（`DuplicateKeyException`）视为已登记并回读既有行。同一摘要的内容必然相同，因此并发结果一致。
- **登记失败**：原子改名成功后索引插入失败 → 文件保留（不可变、内容等于摘要），下一次同摘要上传走"补登记"分支自愈；
  改名失败 → 删除临时文件并返回 `code=500`「发布包存储不可用…」，不产生索引行。
- **不可变边界**：健康文件不得被任何分支覆盖；只有"文件缺失/损坏"才允许原子替换，替换内容始终等于摘要。
- 阶段 2 不触碰目标机已分发的包，也不执行安装。

### 3. R3 契约：双端一致的格式 v1 大小上限与流式处理

**唯一常量表**（主节点校验器与执行器构建/校验工具必须使用同一组数值；两处都不得整份读入内存）：

| 项 | 上限 | 判定对象 |
| --- | --- | --- |
| 压缩包（`.tar.gz`） | **1 GiB**（1 073 741 824 字节） | 输出/输入文件字节数 |
| 单个成员 | **2 GiB** | 解压后成员字节数 |
| 全部成员解压总计 | **4 GiB** | 解压后字节数之和 |
| 归档成员数 | **64**（格式 v1 实际为 6） | tar 条目数 |

| 侧 | 定稿要求 |
| --- | --- |
| 构建（`packager package`） | ①打包前按流式读取校验载荷成员大小与总计；②写 tar/gzip 全程流式，不整份读入镜像 tar 或任何成员；③写出后检查 `.tar.gz` ≤ 1 GiB；④任一超限：**删除未完成的输出文件**、非零退出、固定前缀错误 `[ERROR] 发布包超出契约上限: <项>（<实际> > <上限>）`。不得留下半成品让 `release.sh` 误判成功 |
| 校验（`packager verify`） | 逐成员流式计算摘要与大小，不 `io.ReadAll` 整个成员；执行同一组上限；只在内存保留有界的 `manifest.json`/`checksums.txt` |
| 主节点（P3 已实现） | 保持同一组上限；`manifest.json`/`checksums.txt` 另各限 256 KiB（[格式契约 §12](../runner-package-format.md)） |
| 双端一致验收 | 同一合法包：`packager verify` 通过 **且** Java 校验器接受，摘要一致；构造超限样本：两端都以各自固定错误拒绝，且构建端不留下输出文件 |
| 不做什么 | 不改六成员集合与顺序、不改可重复构建要求、不改 manifest 字段（因此格式版本仍为 1） |

### 4. R4 契约：阶段 3 安装前的模板兼容判定

**语义**：包是**不可变**的，旧包会一直存在；阶段 3 安装任何候选包（含阶段 2 已入库的旧包）之前，必须机械判定该包的启动模板
是否符合"节点专属凭据只从 `0600` YAML 读取"的约定。**不兼容即拒绝安装**，且拒绝必须发生在**轮换 token 或写远端之前**。

**占位符词汇（新增 `{{PRIVATE_CONFIG}}`，其余沿用）**

| 占位符 | 渲染值 |
| --- | --- |
| `{{RELEASE_DIR}}` | `/opt/flowops/runner/releases/<package-sha256>` |
| `{{IMAGE_REFERENCE}}` | `manifest.image.reference` |
| `{{PRIVATE_CONFIG}}` | `/opt/flowops/runner/private/<nodeKey>.yaml`，`<nodeKey>` = UTF-8 `runnerId` 的 SHA-256 小写十六进制（沿用[一键安装计划](2026-09-28-runner-configuration-and-one-click-install-plan.md)） |

**判定标准（对包内 `templates/*` 文本做机械检查，大小写不敏感）**

| 模板 | 必须存在 | 必须不存在 |
| --- | --- | --- |
| `templates/flowops-executor.service`（native） | `ExecStart=` 指向 `{{RELEASE_DIR}}/bin/flowops-executor`，且带 `--config {{PRIVATE_CONFIG}}` | `EnvironmentFile`、任何 `token` 赋值、把 `FLOWOPS_RUNNER_ID`／`FLOWOPS_MASTER_ADDR`／`FLOWOPS_RUNNER_TOKEN` 作为环境注入 |
| `templates/docker-compose.yaml`（container） | 只读挂载 `{{PRIVATE_CONFIG}}:/etc/flowops/executor.yaml:ro`，且 `FLOWOPS_CONFIG` 指向容器内固定路径 `/etc/flowops/executor.yaml` | `env_file`、`FLOWOPS_RUNNER_TOKEN`、把凭据写进 `environment:`；允许保留非敏感项 `APP_ENV`、`FLOWOPS_RUNNER_VERSION` |

| 项 | 定稿规则 |
| --- | --- |
| 判定时机 | 阶段 3 安装作业内，**生成/轮换 token 之前**；不通过则作业立即失败，`nexa_node.token` 哈希与目标机均不被改动 |
| 拒绝响应 | 固定错误码 `TEMPLATE_INCOMPATIBLE`，文案「该发布包的启动模板不符合节点凭据读取约定，不能安装」；作业记录保留阶段与包摘要，允许修复后重试 |
| 判定对象 | 所有候选包，含阶段 2 已入库的旧包（其模板仍是 `EnvironmentFile`/`env_file` 形态 → 判不兼容） |
| 读取方式 | 主节点需能从**已存储的 `.tar.gz`** 中流式读取 `templates/*` 文本（不解压落盘、不执行），属 B3 的"包内容读取/校验模块" |
| 与阶段 2 的边界 | 阶段 2 的分发 API **不实现**该门禁：旧包仍可分发、分发记录仍有效，但**不得**被判定为"可安装"。分发成功 ≠ 可安装 |
| 不做什么 | 不回写不可变包、不新增 manifest 字段、不在阶段 2 分发链路里安装或启动任何东西 |

### 5. 本次新增的失败码（合并清单，供 B1/B2/F1 实现与验收对照）

| 失败码 | 出现阶段 | 固定文案 | 产生位置 |
| --- | --- | --- | --- |
| `SSH_CONFIG_CHANGED` | 分发运行期 | SSH 设置已变更或已被删除，需重新配置并测试连接后再分发 | 后台任务读取目标后、建会话前 |
| `SSH_NOT_VERIFIED` | 分发运行期 | 该节点 SSH 最近一次测试未通过，请重新测试连接后再分发 | 同上（版本一致但最近测试非 `CONNECTED`） |
| `STORE_CHECKSUM_MISMATCH` | 分发运行期 | 主节点存储的发布包与摘要不一致，请重新上传该包 | 本地源文件校验后、建会话前 |
| `TEMPLATE_INCOMPATIBLE` | 阶段 3 安装 | 该发布包的启动模板不符合节点凭据读取约定，不能安装 | 安装作业内、token 轮换前 |

已有失败码不变：连接/认证/私钥类继续复用阶段 1 `SshTestResultCode`；`REMOTE_*`/`UPLOAD_*`/`MASTER_RESTARTED`/`STORE_READ_FAILED`/`INTERNAL_ERROR` 语义不变。
接口级（请求级）失败仍为 `code=400/403/404/409` + 固定 `msg`，不把运行期失败码混进请求级响应。

### 6. 对下行的影响（各行按此实现与验收）

| 行 | 必须实现 | 验收口径（可判定） |
| --- | --- | --- |
| B1 | 记录字段 `ssh_config_version` + 迁移 `V3_1_9`；后台单次读取 + 三项判定 + 使用同一对象建会话；两个新失败码；`PackageDistributionVO.sshConfigVersion` | 排队后修改 host/port/指纹/密钥别名（或重测失败、删除目标）→ 记录 `FAILED` 且目标机无任何写入；配置不变 → 正常分发；绑定版本可在接口响应中读到 |
| B2 | 上传五分支语义 + `repaired` 字段；分发前本地源校验与 `STORE_CHECKSUM_MISMATCH`；并发/登记失败/临时文件清理测试 | 数据库有行文件缺失、文件损坏、文件有而数据库无行、登记失败、并发同摘要上传；健康文件 mtime/内容不变；分发源摘要不符时不建会话 |
| G1 | 流式构建与校验 + 统一上限表 + 超限删除半成品输出 | 大载荷样本内存有界；超限拒绝且无残留输出；两次构建摘要一致；`packager verify` 与 Java 校验器接受同一合法包 |
| F1 | 展示 `SSH_CONFIG_CHANGED`／`SSH_NOT_VERIFIED`（提示去重测）与 `repaired`；轮询与失败重试 | 页面对两个新码给出可操作提示；构建退出码 0 |
| B3／G2／V2（阶段 3） | 模板兼容门禁与模板更新；`TEMPLATE_INCOMPATIBLE` 在 token 轮换前生效 | 旧 `executor.env` 模板拒绝、新 YAML 模板通过；真实安装中 `docker inspect` 与日志无 token |

### 7. 本次定稿明确不改的部分

- **包格式 v1 不变**：六成员、成员顺序、权限、`manifest.json` 字段集、`checksums.txt` 规则、可重复构建要求、
  `packageFormatVersion = 1`；因此 P3-C 的真实固件与既有入库包继续有效。
- **阶段 2 分发职责不变**：只投递不可变包，不解压、不执行、不写节点凭据、不安装、不启动、不轮换 token。
- **阶段 1 信任边界不变**：严格主机密钥（算法 + 指纹双校验）、私钥别名与权限规则、`config_version` 的既有语义。
- **`nexa-protocol` 无修改**。

### 8. D0 自检对照（对应 D0 行的"验证"列）

| 对照项 | 结论 |
| --- | --- |
| 现有 `configVersion` | `nexa_node_ssh_target.config_version` 已存在（迁移 `V3_1_5`：`BIGINT NOT NULL DEFAULT 0`），`PUT /ssh` 覆盖设置时自增并清空 `latest_test_seq` 与旧 `lastTest`。R1 直接复用，不新增 SSH 表字段；新增的只是分发记录上的 `ssh_config_version` |
| 包格式 v1 | 本节与格式契约 §13 均未新增/删除归档成员、未改 manifest 字段集，`packageFormatVersion` 仍为 `1`；P3-C 的真实 Go 固件与已入库包继续有效 |
| 既有错误码 | 新增 3 个分发/存储码（`SSH_CONFIG_CHANGED`、`SSH_NOT_VERIFIED`、`STORE_CHECKSUM_MISMATCH`）与 1 个阶段 3 码（`TEMPLATE_INCOMPATIBLE`）。与阶段 1 `SshTestResultCode`（19 个取值）及现有 `PackageDistributionErrorCode`（13 个取值）逐一比对：无重名、无同码多义；请求级仍是 `code=400/403/404/409` + 固定 `msg` |
| P5 边界 | D0 不产生任何验收结论。V1 仍须真实主节点 + 已验证 SSH 目标 + 真实镜像体量 + 生产 MySQL 迁移证据；进程内 SSHD 与模拟 MySQL 不能替代 |
| O1—O3 边界 | R4 只定稿"判定标准 + 拒绝响应 + 时机"，`G2`/`B3`/`V2` 的实现与真实验收留在阶段 3；阶段 2 分发 API 不实现该门禁，旧包仍可分发但不得被判为可安装 |
| 代码只读 | D0 只修改 3 份文档（本文件、`docs/frontend-api/node-package-api.md`、`docs/runner-package-format.md`），未修改 `flowops-app` 或执行器仓库任何代码；三处均为**追加**，原有内容字节未变（追加时以 SHA-256 前缀校验） |
| 跨仓影响 | 无需要修改 `nexa-protocol` 的项；执行器仓库的改动需求已分别落到 G1（流式与上限）与 G2（模板）行，主节点落到 B1/B2/B3，前端落到 F1 |

### 9. D0 契约索引：契约在哪、谁读哪一节、以谁为准

D0 的契约分散在三份文档里（本文件是总入口，另两份是面向实现者的细化）。下表是**唯一权威位置表**；
各行的执行代理按 §9.2 的"最小必读集"读取即可，不必通读全部文档。

#### 9.1 权威位置表

| 契约主题 | 权威位置（章节号 + 标题） | 该位置的读者 |
| --- | --- | --- |
| R1 分发绑定已验证 SSH 配置版本：字段、时序、失败码 | `docs/issues/2026-09-28-phase-2-runner-package-design-review.md` §1「R1 契约：分发绑定已验证的 SSH 配置版本」 | B1、V1 |
| R1 对外字段与错误码（`sshConfigVersion`、`SSH_CONFIG_CHANGED`、`SSH_NOT_VERIFIED`） | `docs/frontend-api/node-package-api.md` §8「2026-09-28 修订（D0 契约）：SSH 配置版本绑定与存储失同步」（§8.1、§8.3、§8.4、§8.5） | B1、F1 |
| R2 索引与本地文件一致性：五个上传分支、`existing`/`repaired`、四种 `msg` | `docs/frontend-api/node-package-api.md` §8.2；语义与边界见本文件 §2「R2 契约：索引与本地正式包文件的一致性」 | B2、F1 |
| R2 分发前本地源校验与 `STORE_CHECKSUM_MISMATCH`、并发/登记失败/清理语义 | 本文件 §2（含"补充规则"）；错误码取值见 §5 与 API §8.3 | B2 |
| R3 双端统一大小上限、流式要求、构建失败与清理语义 | `docs/runner-package-format.md` §13「2026-09-28 修订（D0 契约）：双端大小上限与阶段 3 模板兼容判定」§13.1；契约要点见本文件 §3 | G1、V1 |
| R3 主节点侧对应上限（含文本成员 256 KiB） | `docs/runner-package-format.md` §12 与 §13.1 第三行 | G1、V1 |
| R4 模板兼容判定标准、占位符词汇、容器内固定路径、判定时机与拒绝响应 | `docs/runner-package-format.md` §13.2；跨阶段边界见本文件 §4「R4 契约：阶段 3 安装前的模板兼容判定」 | G2、B3、V2 |
| 节点凭据 YAML 的路径/权限/消费方式（R4 的上游契约） | `docs/2026-09-28-runner-configuration-and-one-click-install-plan.md`（"目标机凭据为节点专属 YAML…"段与 C2/C3 行） | G2、B3、V2 |
| 新增失败码合并清单与固定文案 | 本文件 §5「本次新增的失败码」 | B1、B2、F1、B3 |
| 各行的实现与验收口径 | 本文件 §6「对下行的影响」 | B1、B2、G1、F1、B3、G2、V1、V2 |
| 明确不改的部分（包格式 v1、阶段 2 分发职责、阶段 1 信任边界） | 本文件 §7 | 全部行 |
| 本索引自身与自检对照 | 本文件 §8、§9 | 全部行 |

#### 9.2 各行的最小必读集

| 行 | 必读（按顺序） | 交付判据（取自"验证"列） |
| --- | --- | --- |
| B1（`D:\project\backend\flowops`） | 本文件 §1 → §5 → §6（B1 行）；`node-package-api.md` §8.1／§8.3／§8.4；阶段 1 计划中 `config_version`／`latest_test_seq`／`lastTest` 的语义说明 | 排队后改 host/port/指纹/密钥别名（或重测失败、删目标）→ 记录 `FAILED` 且目标机零写入；配置不变正常分发；绑定版本可从接口读到 |
| B2（`D:\project\backend\flowops`） | 本文件 §2 → §5 → §6（B2 行）；`node-package-api.md` §8.1／§8.2 | 索引有行文件缺失/损坏、文件有而索引无行、登记失败、并发同摘要上传；健康文件内容/mtime 不变；源摘要不符时不建会话 |
| G1（`D:\project\go\flowops-executor`） | 格式契约 §13.1 → §2 → §5（成员与确定性要求不变）；本文件 §3 → §6（G1 行） | 大载荷内存有界；超限拒绝且无残留输出；两次构建摘要一致；`packager verify` 与 Java 校验器接受同一合法包 |
| F1（`D:\project\front\flowops-front`） | `node-package-api.md` §8（§8.1—§8.5）→ 本文件 §6（F1 行）；不改包内部与凭据 | 两个新失败码给出可操作提示；`repaired` 展示；构建退出码 0 |
| B3（`D:\project\backend\flowops`，阶段 3） | 本文件 §4 → §5 → §6（B3 行）；格式契约 §13.2；一键安装计划的凭据 YAML 段 | 旧 `executor.env` 模板拒绝、新 YAML 模板通过；拒绝发生在 token 轮换或远端写入之前 |
| G2（`D:\project\go\flowops-executor`，阶段 3 O2） | 格式契约 §13.2（必须/禁止清单与占位符词汇）；本文件 §4；一键安装计划的凭据 YAML 段 | `--config`／只读挂载 + `FLOWOPS_CONFIG`；`docker inspect` 与模板中无 token |
| V1（协调者验收，阶段 2 P5） | 本文件 §6（V1 行）→ §8；阶段 2 计划的 P5 行与验收记录 | 真实主节点 + 已验证 SSH 目标 + 真实镜像体量 + 生产 MySQL 迁移证据；不得以进程内 SSHD/模拟 MySQL 替代 |
| V2（协调者验收，阶段 3） | 本文件 §4 → §6（V2 行）→ §8；阶段 3 验收记录 | 旧包拒绝且不轮换凭据；新包双模式上线；`docker inspect` 与日志无 token |

#### 9.3 冲突与变更纪律（实现前必读）

1. **同一主题多处描述时**，以 §9.1"权威位置"列指定的文件与章节为准。
2. 本文件 §1—§5 是契约**总入口**与跨仓边界的最终裁定；`node-package-api.md` §8、`runner-package-format.md` §13 是面向实现的细化，
   它们的**字段名、错误码取值、常量数值、占位符词汇**必须与本节一致。发现不一致：**按 §9.1 指定的权威位置实现**，
   同时上报协调者，不得自行在任一仓库改动契约。
3. **旧章节只作历史记录**：本文件的"审阅结论与问题清单"（R1—R4 行）、`node-package-api.md` §1—§7 与
   `runner-package-format.md` §1—§10 描述的是**修订前的现状与原始意图**；实现请从 D0 后的契约出发，不要按旧章节实现。
   注意 **以下修订仍然生效**、不是历史记录：格式契约 §11（配置统一修订）、§12（主节点 P3 校验实现修订）、§13（D0 契约），
   `node-package-api.md` §8（D0 契约），以及本文件 §1—§9。只有当它们与 D0 的 R1—R4 契约冲突时，
   才按 §9.1 指定的权威位置为准。
4. **代码是当前行为的唯一事实来源，契约描述的是目标行为**：D0 未修改任何代码，因此 B1/B2/G1/F1/B3/G2 的契约内容
   **目前尚未在代码中生效**。任何行都不得把"契约已定稿"表述为"功能已实现"。
5. **格式版本未变**：D0 没有新增/删除归档成员、没有新增 manifest 字段，`packageFormatVersion` 仍为 `1`；
   若后续确需改动成员集合或 manifest 字段，先按格式契约 §10 修订并升级版本，再改依赖行。
6. **变更只能从权威位置发起**：先改 §9.1 指定的权威文件，再由协调者同步其余两处；不得单仓、单行或单文件静默改契约
   （格式契约 §13.3 与 §10 同此纪律）。

## B1／B2 实施记录（2026-09-28，仅追加）

> 本节记录[开发顺序表](#开发顺序表) 中 **B1**（R1：分发绑定已验证的 SSH 配置版本）与 **B2**（R2：索引与本地包文件一致性）
> 的实现与验证。契约按 **D0**（本文件 §1—§9、[`node-package-api.md`](../frontend-api/node-package-api.md) §8、
> [`runner-package-format.md`](../runner-package-format.md) §13）执行；上文与 D0 节均未改动。
> 真实链路验收（V1）仍未完成，见文末。

### 1. 落地内容

| 行 | 落地 | 文件 |
| --- | --- | --- |
| B1 | 迁移新增 `nexa_node_package_distribution.ssh_config_version BIGINT NULL` | `flowops-app/src/main/resources/sql/migration/V3_1_9__add_distribution_ssh_config_version.sql` |
| B1 | 实体字段 `sshConfigVersion`；建记录时写入当时已通过测试的 `config_version` | `entity/NexaNodePackageDistribution.java`、`service/nodepackage/RunnerPackageDistributionService.java` |
| B1 | 后台执行前**只读一次**目标并按序核对：绑定非空 → 当前 `config_version` 相等 → 最近测试为 `CONNECTED`；通过后**用这同一个对象**建会话 | `RunnerPackageDistributionService#runDistribution` / `#checkSshBinding` |
| B1 | 失效失败码 `SSH_CONFIG_CHANGED`（版本变或目标删除/未绑定）与 `SSH_NOT_VERIFIED`（版本一致但最近测试非 `CONNECTED`），均在建会话之前 | `service/nodepackage/PackageDistributionErrorCode.java` |
| B1 | 响应字段 `sshConfigVersion` | `dto/PackageDistributionVO.java` |
| B2 | 索引与文件一致性判据：`isHealthy`（存在 + 大小 + SHA-256）与 `commitReplacing`（原子替换，仅修复路径使用） | `service/nodepackage/LocalRunnerPackageStore.java` |
| B2 | 上传五分支：全新 / 健康复用（不动文件）/ 修复文件 / 补登记 / 损坏替换；返回 `existing` + 新增 `repaired` + 分支固定文案 | `service/nodepackage/RunnerPackageService.java`、`dto/RunnerPackageVO.java`、`controller/NodePackageController.java` |
| B2 | 分发前本地源校验（大小 + SHA-256），不符即 `STORE_CHECKSUM_MISMATCH`，**在建立 SSH 会话之前** | `service/nodepackage/RunnerPackageDistributor.java`、`PackageDistributionErrorCode.java` |

响应字段与失败码的对外契约在 D0 已定稿（`node-package-api.md` §8.1—§8.3），本次实现与其逐字一致，**未新增未记录的字段或错误码**。

### 2. 验证证据（2026-09-28，`./mvnw.cmd test`）

阶段 2 定向测试 95 项通过；`flowops-app` 全量 **228 项通过、0 失败、2 跳过**（跳过的是既有 `NodeSshServiceTest` 中需 POSIX 的符号链接用例）。

| 行 | 验收项（取自各行"验证"列） | 用例 |
| --- | --- | --- |
| B1 | 建记录时绑定已验证版本 | `bindsVerifiedSshConfigVersionWhenQueueing`：插入的记录 `sshConfigVersion` 等于当时目标的 `config_version` |
| B1 | 排队后修改 host/port/指纹/密钥别名 → 任务失败 | `failsWhenSshConfigChangedAfterQueueing`：请求级前置看到 v7、后台看到 v8 → `FAILED(SSH_CONFIG_CHANGED)`，且**分发器从未被调用**（不可能建立会话） |
| B1 | 排队后目标被删除 | `failsWhenSshTargetWasDeletedAfterQueueing` → `SSH_CONFIG_CHANGED`，分发器未被调用 |
| B1 | 排队后重测失败（版本未变） | `failsWhenLatestSshTestIsNoLongerConnected` → `SSH_NOT_VERIFIED`，分发器未被调用 |
| B1 | 未绑定版本的历史记录 | `failsLegacyRecordWithoutBoundSshVersion` → `SSH_CONFIG_CHANGED`，分发器未被调用 |
| B1 | 配置不变正常分发 | `distributesWithBoundVersionWhenNothingChanged`（服务级）+ `unchangedSshConfigDistributesThroughServiceFlow`（真实 SSH/SFTP）→ `SUCCEEDED`，会话用的是后台读取的那一份目标 |
| B1 | **真实链路：失效时远端零写入** | `changedSshConfigAfterQueueingFailsWithoutAnyRemoteWrite`：进程内真实 SSHD；排队后把 `config_version` 改为 1 并改 host/密钥别名 → 记录 `FAILED(SSH_CONFIG_CHANGED)`，**记录的远端命令为空**、目标机无任何文件 |
| B2 | 索引有行、文件健康 → 不覆盖 | `duplicateUploadWithHealthyFileKeepsItUntouched`：`existing=true`、`repaired=false`、内容与**修改时间**均不变、不写索引 |
| B2 | 索引有行、文件缺失 → 修复 | `duplicateUploadRepairsMissingFile`：`repaired=true`、文案"已按摘要修复存储文件"、文件内容等于上传字节、不新建索引行 |
| B2 | 索引有行、文件损坏 → 修复 | `duplicateUploadReplacesCorruptFile`：同上，内容被原子替换为上传字节 |
| B2 | 文件有而索引无行 → 补登记 | `uploadBackfillsIndexWhenFileExistsWithoutRow`：`existing=false`、`repaired=false`、文案"已补登记"、文件未被重写、写入一条索引 |
| B2 | 同名文件损坏且无索引 → 替换并登记 | `uploadReplacesCorruptFileWithoutIndexAndRegisters`：`repaired=true` |
| B2 | 登记失败后可自愈 | `registrationFailureKeepsFileSoNextUploadBackfills`：首次 `insert` 抛错后文件仍在；再次上传走"补登记"成功，无需重新传输 |
| B2 | 并发同摘要上传 | `concurrentSameDigestUploadsBothSucceedWithOneIndexRow`：两线程同摘要上传均成功，索引冲突按已登记处理，文件内容正确、无临时文件残留 |
| B2 | 存储层判据 | `storeDetectsMissingOrCorruptFileAndReplacesAtomically`、`storeNeverOverwritesExistingPackageAndCleansTempFiles`、`storeRejectsNonHexDigestToPreventPathTraversal` |
| B2 | **分发源摘要一致**（源被改坏 → 不传输） | `refusesDistributionWhenStoredSourceDoesNotMatchDigest`：真实 SSHD 链路上把主节点源文件改坏 → `STORE_CHECKSUM_MISMATCH`，**远端命令为空**、目标机无文件 |
| B2 | 对外文案与字段 | `NodePackageControllerTest.uploadReportsNewPackageAndPassesOperator` / `uploadReportsExistingPackageIdempotently` / `uploadReportsRepairedStoreFile` / `uploadReportsIndexBackfill` |

### 3. 边界与不做的事

- **未改阶段 1 的主机密钥策略**：分发会话仍走"算法 + 指纹双校验"，`RunnerPackageSshConsistencyTest` 6 项继续通过与阶段 1 判定一致。
- **未改执行器仓库与前端**：G1（Go 流式与上限）、F1（前端展示新码与 `repaired`）仍待各自实施；契约已由 D1 前的 D0 定稿。
- **不覆盖健康包**：健康文件只在"缺失/损坏"时才被原子替换；`commit` 的"已存在则保留"语义保持不变。
- **失败零远端动作**：`SSH_CONFIG_CHANGED`／`SSH_NOT_VERIFIED`／`STORE_CHECKSUM_MISMATCH` 都在建立 SSH 会话之前判定，目标机不会出现新文件或残留 `.part`。
- **分发成功 ≠ 可安装**：阶段 2 仍不写入节点凭据、不安装、不启动、不轮换 token；R4 的模板门禁属阶段 3（B3/G2/V2）。

### 4. 仍未完成（不得据此关闭阶段 2）

- **V1 真实验收**：真实 Linux 目标机、真实 MySQL 执行 `V3_1_7`/`V3_1_8`/**`V3_1_9`**、真实镜像体量与磁盘余量、真实中断重试，
  以及阶段 1 H1/S4——本行只用到进程内真实 SSHD 与模拟持久层。
- **F1**：前端尚未展示 `SSH_CONFIG_CHANGED`／`SSH_NOT_VERIFIED`／`repaired`；契约见 `node-package-api.md` §8.5。
- **G1**：构建端流式与统一上限（格式契约 §13.1）尚未实施，因此超限包仍可能被构建出来（主节点会按契约拒绝）。
- 前端 `npm run build` 在本执行环境仍受 `esbuild spawn EPERM` 限制，不能据此判断页面构建结果。

## G1 实施记录（2026-09-28，仅追加）

> 本节记录[开发顺序表](#开发顺序表) 中 **G1**（R3：构建与校验流式化 + 双端统一大小上限）的实现与验证，
> 仓库为 `D:\project\go\flowops-executor`。契约按 **D0** 执行：权威位置是
> [`runner-package-format.md`](../runner-package-format.md) §13.1（本文件 §3 为要点对照，§6 为验收口径）。
> 上文、D0 节与 B1／B2 节均未改动；上节末"G1 尚未实施"的状态**自此不再成立**。
> 只改 `deploy/packager/**`、`deploy/release.sh`、`deploy/release.ps1`、对应测试与 `deploy/README.md`；
> 未改 `runner/**` 行为、未改六成员集合与顺序、未改 manifest 字段（`packageFormatVersion` 仍为 `1`）、
> 未改主节点与 `nexa-protocol`。实现细节与证据表另见执行器仓库 `deploy/README.md` §5—§6。

### 1. 落地内容

| 项 | 落地 | 文件 |
| --- | --- | --- |
| 唯一上限常量表 | 压缩包 1 GiB、单成员 2 GiB、解压总计 4 GiB、成员数 64、`manifest.json`/`checksums.txt` 各 256 KiB；与主节点 `RunnerPackageSettings` / `RunnerPackageValidator.TEXT_MEMBER_LIMIT` 数值一致 | `deploy/packager/limits.go` |
| 固定超限文案 | `limitError` → `发布包超出契约上限: <项>（<实际> > <上限>）`；CLI 以 `[ERROR] ` 前缀输出，且超限错误不被步骤前缀包装（`gz.Close` 才抛出时也保持原样） | `limits.go`、`packager/main.go` |
| 构建流式 | 打包前按 `os.Stat` 核对载荷成员大小与总计；逐成员流式计算 sha256；写 tar/gzip 时 `io.Copy` 直接从载荷文件取数（不再 `os.ReadFile` 整个镜像 tar）；限流写入器在压缩包越限时**立即**失败 | `deploy/packager/packager.go` |
| 构建失败语义 | 输出先写同目录临时文件，任一步失败删除临时文件、成功才原子改名；`--out` 上已有的包不被改写；`--out` 目录按需创建 | `packager.go` |
| 校验流式 | 整包与逐成员流式校验（不再 `io.ReadAll`），只在内存保留有界的结构性文本成员；执行同一组上限；新增与 Java 侧一致的"tar 结尾后不得再有数据"检查 | `deploy/packager/verify.go` |
| 编排脚本 | `release.sh` / `release.ps1` 在打包或自检失败时删除本次 `--out`（不影响 `dist/` 中其他产物） | `deploy/release.sh`、`deploy/release.ps1` |

### 2. 验证证据（2026-09-28，Windows + WSL2；Go 1.26.3；本机**无容器工具**）

| 验收项（G1 行"验证"列） | 结果 |
| --- | --- |
| 大载荷样本内存有界 | ✅ 单测 `TestLargeMemberUsesBoundedMemory`：24 MiB 不可压缩成员，构建 + 校验全过程采样堆占用，峰值增长 ≈ **1.3 MiB**（预算 8 MiB） |
| 超限拒绝且无残留输出 | ✅ 单测 `limits_test.go`：压缩包/单成员/解压总计/成员数/文本成员五类超限均以固定文案失败；失败后输出路径与 `*.partial-*` 均不存在；预置旧包时旧包字节不变。CLI 演练：稀疏 2 GiB+1 载荷成员 → exit 1、`[ERROR] 发布包超出契约上限: 单个成员 images/flowops-executor.tar（2147483649 > 2147483648）`、输出目录为空 |
| 两次构建摘要一致 | ✅ 单测 `TestBuildPackageDeterministic`；并额外验证：**重写后重新打包后端固件载荷，得到与原固件逐字节相同的包**（prod `02c16ee9…`、993 字节） |
| Go `verify` 与 Java 校验器接受同一合法包 | ✅ Go `verify` 接受 prod/dev 两个已入库固件（`…/src/test/resources/runner-package/`）；Java `RunnerPackageRealFixtureTest` 4 项通过（`mvn -o -Dtest=RunnerPackageRealFixtureTest test` → BUILD SUCCESS），摘要一致 |
| 金标准回归（自加） | ✅ 单测 `TestGoldenPackageMatchesBackendFixture` 断言打包结果等于后端固件 SHA-256；该断言绑定 Go `go1.26.3`（.tar.gz 摘要取决于 deflate 实现），升级 Go 时须重新生成固件并同步期望值 |
| 回归面 | ✅ `go test ./...` 全通过（`config`、`deploy/packager`）；`go vet ./...`、`gofmt -l` 干净；`release.ps1 -SkipImage` 实跑通过；`release.sh` 过 `bash -n`、`release.ps1` 过 PowerShell 解析 |

### 3. 边界与不做的事

- **格式 v1 不变**：六成员、成员顺序、权限、`manifest.json` 字段集、`checksums.txt` 规则、可重复构建要求均未改动；重写后产出的包与原实现**逐字节相同**，因此已入库固件与 P3-C 结论继续有效。
- **不改主节点与协议**：Java 侧上限本就存在（`PACKAGE_TOO_LARGE`/`MEMBER_TOO_LARGE`/`MEMBER_INVALID`），本次只是让构建端与之一致；`nexa-protocol` 无修改。
- **不改打包产物身份**：`verify` 新增的"tar 结尾后仍有数据"检查只拒绝畸形包（Java 侧同判定），不影响合法包。
- **阶段 2 职责不变**：不写节点凭据、不安装、不启动、不轮换 token；R4（G2/B3/V2）仍属阶段 3。

### 4. 仍未完成（不得据此关闭阶段 2）

- **构建机上的端到端补充**：真实镜像载荷接近 1 GiB 时的行为、`release.sh` 的**完整**打包路径（本机 WSL 无 Go、无 Docker，只做了语法检查与 `--skip-image` 实跑；打包与自检分支的命令行及失败清理语义已由 CLI 演练与单测覆盖）。
- **跨端超限样本对照**：Java 侧超限拒绝已有测试覆盖，但"同一样本两端各自固定错误拒绝"未联合构造。
- **V1 真实验收**：真实主节点 + 已验证 SSH 目标 + 真实镜像体量 + 生产 MySQL 迁移证据仍须协调者补齐；本节不产生 V1 结论。

