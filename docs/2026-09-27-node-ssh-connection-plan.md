# 阶段 1：主节点到子节点宿主机的 SSH 连接验证

表单字段来源及 Windows/Linux 具体操作见[节点管理 SSH 设置操作指南](2026-09-27-node-ssh-setup-guide.md)。

> 状态（2026-09-27 审阅更新）：S1 API 契约已定稿；S2 后端与 S3 前端代码均已实现。后端 SSH 专项 27 个测试、前端节点页 10 个测试和前端构建通过。审阅发现下文 R1—R3 尚需修复；R4 生产数据库迁移与 S4 真实 SSH 联调尚未验证，因此本阶段**尚未完成**。现有自动化验证不等于真实宿主机连接成功，也不证明生产库已有新表。
>
> 执行方记录（R1、R3 修复）：R1 已为 SSH 设置加入 `config_version` 与 `latest_test_seq`（迁移 `V3_1_5__add_ssh_target_test_guard.sql`），测试开始时在事务内读不可变快照并登记序号，结束仅在两者都匹配时写回，否则返回 `TEST_OBSOLETE` 且不写入 `lastTest`；`PUT` 递增版本并清空序号与旧结果。R3 已在存在性检查与读取前拒绝符号链接、按真实路径确认落在真实 `key-dir` 内，读取与 `keyFileExists` 共用同一规则并以 `NOFOLLOW_LINKS` 打开。后端 `flowops-app` 全量 125 项测试通过（0 失败；2 项真实符号链接用例需 POSIX，本机 Windows 跳过，须在 Linux/CI 复跑）。R2、R4、S4 仍未完成。
>
> 执行方记录（H1.2、H1.3）：已定稿并实现显式 `hostKeyAlgorithm`（`ED25519`/`ECDSA`/`RSA`）与单一 `hostKeySha256` 配对的字段、存储（迁移 `V3_1_6`）、兼容与校验规则；客户端按所选算法限制可协商主机密钥并**双重校验算法与指纹**，新增结果码 `HOST_KEY_ALGORITHM_UNAVAILABLE`、`HOST_KEY_ALGORITHM_REQUIRED`。`NodeSshServiceTest` 33 项通过（2 项需 POSIX）。H1.4 前端代码已存在，但专项测试仍有运行器异常；H1.1 目标机/容器诊断和 H1.5 真实环境重测未完成，H1 尚未关闭。详见文末复核记录。
>
> 真实联调新增阻塞 H1（2026-09-27）：`HOST_KEY_MISMATCH`，人工核对的 ED25519 主机指纹与后端握手实际收到的指纹不同。算法协商不一致是待验证假设，尚不能据此排除连错主机或主机密钥变更；排查和修复顺序见文末。H1 解决并重新测试前，S4 不得判定通过。

## 本阶段契约与边界

节点以已有 `nexa_node.runner_id` 为身份。SSH 目标是该节点所在的**宿主机**，不是执行器容器 IP；它与当前 Nexa Protocol 的子节点连接是两条独立链路。节点管理页的“节点登记”保留，新增“SSH 设置”和“测试连接”。首版支持 Linux amd64 目标和 SSH 公钥认证；私钥由管理员预先放到主节点容器可读的 `/data/flowops/ssh-keys/<keyAlias>`，文件权限为 `0600`，数据库与 API 只保存别名。主节点现有部署脚本已挂载 `/data/flowops`，但运行环境仍需核对实际目录权限。

SSH 设置固定包含 `host`、`port`（默认 22）、`username`、`keyAlias`、由管理员核对后填入的 `hostKeySha256`。主节点严格校验远端主机密钥，不采用自动信任新密钥。测试连接依次验证 TCP/SSH 握手、主机密钥、私钥认证、只读命令 `true`、SFTP 通道打开；全程不向远端写文件。连接、认证、命令和 SFTP 各有有界超时。API 只返回 `CONNECTED`、`CONNECT_TIMEOUT`、`HOST_KEY_MISMATCH`、`AUTH_FAILED`、`COMMAND_FAILED`、`SFTP_FAILED` 等结果码和时间，不返回私钥、完整异常栈或远端敏感输出。

本阶段继续沿用 [NodeController](../flowops-app/src/main/java/com/nexa/flowops/controller/NodeController.java) 的**仅超级管理员**节点管理边界；普通已登录用户仍只可看在线节点。前端隐藏入口不代替后端授权。SSH 设置与测试不创建或启动 runner，不改变 `nexa_node` 注册 token，也不执行 Docker 操作。

| 仓库绝对路径 | 契约负责人 | 本阶段范围 |
| --- | --- | --- |
| `D:\project\backend\flowops` | 主节点 API、持久化、SSH 连接及权限；协调者定稿接口 | `flowops-app` 的节点管理模块、SQL 迁移、测试和本文档 |
| `D:\project\front\flowops-front` | 消费后端定稿 API | 节点管理页的 SSH 设置与测试连接操作 |
| `D:\project\go\flowops-executor` | 无 | 不修改 |
| `D:\project\mix\nexa-protocol` | 无 | 不修改 |

## 开发顺序表

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| S1 | `D:\project\backend\flowops`（协调者） | 定稿 `GET/PUT /api/nodes/registry/{runnerId}/ssh` 和 `POST /api/nodes/registry/{runnerId}/ssh/test` 的请求、脱敏响应、结果码与超时；确认当前主节点容器可读密钥目录 | 无 | 本文档、API 契约文档；只读核查现有部署脚本/配置 | 可供后端和前端分别实现的 API 契约 | 与现有 `/api/nodes/registry/**`、`/api/nodes/{runnerId}` 路由和权限核对 | 不实现 SSH、分发或页面 |
| S2 | `D:\project\backend\flowops` | 新增 `nexa_node_ssh_target`（runnerId 唯一、host、port、username、keyAlias、hostKeySha256、最后测试结果/时间）；使用 SSH 客户端库执行严格主机密钥校验、认证、`true` 和 SFTP 通道测试；实现 S1 API，所有写入/测试只允许超级管理员 | S1 | `flowops-app/src/main/java/com/nexa/flowops/controller/NodeController.java` 或新增节点 SSH 控制器、`service/node/**`、相关 DTO/mapper/entity、`flowops-app/src/main/resources/sql/migration/**`、对应测试、`flowops-app/pom.xml` | 可保存目标并返回可诊断的连接结果；私钥只按受限别名从本地目录读取 | 正常连接、错误主机指纹、错误密钥、超时、缺密钥、普通用户 403、敏感值不入日志的测试 | 不写远端文件，不安装执行器，不改注册 token |
| S3 | `D:\project\front\flowops-front` | 在节点管理的“节点登记”每行增加 SSH 设置及测试连接；编辑 host/port/username/keyAlias/hostKeySha256，展示测试时间和结果码对应中文原因；仅超级管理员显示操作 | S1、S2 API 定稿 | `src/pages/NodeList.tsx`、`src/api/nodes.ts`、`src/types/**`、相关测试 | 可由页面完成 SSH 设置和测试连接 | 表单校验、成功/失败回显、无权限入口隐藏、刷新后结果保持 | 不出现分发、安装、Docker/原生启动按钮 |
| S4 | `D:\project\backend\flowops`（集成验收） | 从实际运行的 FlowOps 主节点容器通过页面测试一台 Linux 宿主机，记录目标、主机指纹核验方式、连接结果和权限测试 | S2、S3、H1.5 | 本文档验收记录；缺陷回流到对应仓库行 | 有环境与结果证据的 SSH 链路验收 | 正确密钥成功，错误指纹/密钥失败；未授权用户不可测试 | 不把“能 ping”或仅 TCP 端口开放当作完成 |

各行受派代理只编辑本行仓库及允许范围。跨仓字段或权限变更先由 S1 协调者更新契约。S4 通过后才开始阶段 2：[执行器包分发](2026-09-27-runner-package-distribution-plan.md)。

## API 契约（S1 定稿，S2/S3 依此实现）

### 路由与权限

节点身份沿用 `nexa_node.runner_id`。三个接口全部**仅超级管理员**，与现有 `/api/nodes/registry/**` 同一判定（`StpUtil` 登录态 + `sys_user.is_super_admin = 1`）；非超级管理员返回 `code=403`，未登录由 Sa-Token 拦截器返回 401。前端隐藏入口不作为授权依据。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/nodes/registry/{runnerId}/ssh` | 读取该节点的 SSH 设置与最近一次测试结果 |
| PUT | `/api/nodes/registry/{runnerId}/ssh` | 新增或覆盖 SSH 设置（全量字段） |
| POST | `/api/nodes/registry/{runnerId}/ssh/test` | 用**已保存**的设置执行一次连接验证 |

响应统一为既有 `Result<T>`：`{code, msg, data}`。

### 请求 / 响应

`PUT` 请求体（全部必填，不允许只改一个字段）：

```json
{
  "host": "10.0.0.5",
  "port": 22,
  "username": "root",
  "keyAlias": "runner-1",
  "hostKeyAlgorithm": "ED25519",
  "hostKeySha256": "SHA256:uL53VNB2cODbKzRjnoENplYrqjNzX1TqxuVCoAZpOb8"
}
```

字段校验（不通过返回 `code=400` 并以 `msg` 指明字段）：

| 字段 | 规则 |
| --- | --- |
| `host` | 1–253 字符，仅允许 `A-Za-z0-9.-_` 与 IPv6 的 `:`；不接受 `scheme://`、端口或空白 |
| `port` | 1–65535，缺省 22 |
| `username` | 1–64 字符，仅允许 `A-Za-z0-9._-` |
| `keyAlias` | 1–128 字符，仅允许 `A-Za-z0-9._-`；不允许路径分隔符，不允许 `.` / `..`（防目录穿越） |
| `hostKeyAlgorithm` | **必填**，取值 `ED25519` / `ECDSA` / `RSA`（大小写不敏感，服务端归一化为大写）；指纹必须是**该算法对应**的目标机主机公钥指纹 |
| `hostKeySha256` | `SHA256:<base64>`；接受带/不带 `SHA256:` 前缀、带/不带 `=` 填充，服务端归一化为 `SHA256:<43 字符无填充 base64>` 后存储；解码后必须为 32 字节 |

`GET` / `PUT` 成功响应的 `data`（**脱敏**：不含私钥内容、不含服务器绝对路径、不含异常栈）：

```json
{
  "runnerId": "runner-1",
  "host": "10.0.0.5",
  "port": 22,
  "username": "root",
  "keyAlias": "runner-1",
  "hostKeyAlgorithm": "ED25519",
  "hostKeySha256": "SHA256:uL53VNB2cODbKzRjnoENplYrqjNzX1TqxuVCoAZpOb8",
  "keyFileExists": true,
  "lastTest": {
    "resultCode": "CONNECTED",
    "message": "连接成功",
    "testedAt": "2026-09-27T16:30:00",
    "durationMs": 812
  }
}
```

- `hostKeyAlgorithm` 与 `hostKeySha256` 是**成对**的：两者指向目标机同一把经管理员核对的主机公钥。
  历史记录（H1.2 之前保存的）该字段为 `null`：`GET` 原样返回，但 `POST .../test` 一律返回
  `HOST_KEY_ALGORITHM_REQUIRED`，**不允许**在未选择算法的情况下继续测试（不静默假定为 ED25519）。

- `keyFileExists`：仅表示主节点容器内该别名的私钥文件是否存在且为普通文件（不返回路径、不返回内容）。
- `lastTest`：从未测试时为 `null`，否则为上一次测试的持久化结果；`PUT` 覆盖设置后清空（旧结果不属于新目标，避免页面展示误导）。
- 节点未登记：`code=404`，`msg="节点未登记: <runnerId>"`。
- 节点已登记但未配置 SSH：`GET` 返回 `code=200` 且 `data=null`（前端展示空表单）；`POST .../test` 返回 `code=400`，`msg="尚未配置 SSH 目标"`。

`POST .../test` 成功响应的 `data`（接口调用成功即 `code=200`；**连接是否成功由 `resultCode` 表达**，便于前端区分"接口失败"与"SSH 失败"）：

```json
{ "resultCode": "AUTH_FAILED", "message": "公钥认证失败", "testedAt": "2026-09-27T16:31:20", "durationMs": 1533 }
```

测试结果同时落库（`last_result_code` / `last_result_message` / `last_tested_at` / `last_duration_ms`），刷新页面后仍可展示。
写回受两个条件保护（R1）：设置版本 `config_version` 与最新测试序号 `latest_test_seq` 都必须与测试开始时一致；
任一不一致说明测试期间设置被改动、或已有更新的测试登记，此时返回 `TEST_OBSOLETE` 且**不写入** `lastTest`
（探测结果只留在服务端日志）。因此 `GET` 返回的 `lastTest` 一定属于当前已保存设置的最新一次测试。

### 结果码（S3 页面按此映射中文原因）

| 结果码 | 含义 | 判定点 |
| --- | --- | --- |
| `CONNECTED` | 连接成功 | TCP/SSH 握手、主机密钥、认证、`true`、SFTP 全部通过 |
| `CONNECT_TIMEOUT` | 连接超时 | TCP 建立或 SSH 握手未在 `connect-timeout` 内完成 |
| `CONNECT_FAILED` | 无法连接 | 端口拒绝、地址不可达、DNS 解析失败 |
| `HOST_KEY_MISMATCH` | 主机密钥不匹配 | 协商到的密钥算法与所选算法不一致，或 SHA-256 指纹与配置不一致，**立即中止**（不继续认证） |
| `HOST_KEY_ALGORITHM_UNAVAILABLE` | 目标机未提供所选算法 | 服务端已完成 SSH 标识交换，但没有本次所选的主机密钥算法（如只启用 RSA 而选了 ED25519） |
| `HOST_KEY_ALGORITHM_REQUIRED` | 尚未选择主机密钥算法 | 旧记录缺少 `hostKeyAlgorithm`，补齐前不允许测试 |
| `AUTH_TIMEOUT` | 认证超时 | 认证未在 `auth-timeout` 内完成 |
| `AUTH_FAILED` | 公钥认证失败 | 远端拒绝该公钥（含用户名不存在） |
| `COMMAND_TIMEOUT` | 只读命令超时 | `true` 未在 `command-timeout` 内结束 |
| `COMMAND_FAILED` | 只读命令失败 | `true` 退出码非 0（如强制命令/受限 shell） |
| `SFTP_TIMEOUT` | SFTP 通道超时 | SFTP 通道未在 `sftp-timeout` 内打开 |
| `SFTP_FAILED` | SFTP 通道失败 | 远端未启用 SFTP 子系统等原因 |
| `KEY_NOT_FOUND` | 私钥不存在 | 容器内 `<key-dir>/<keyAlias>` 不存在或不是普通文件 |
| `KEY_PERMISSION_TOO_OPEN` | 私钥权限过宽 | 私钥对 group/other 可读（要求 `0600` 或更严） |
| `KEY_ALIAS_INVALID` | 私钥别名非法 | 别名不符合白名单或指向目录 |
| `KEY_UNREADABLE` | 私钥不可读 | 读取失败、格式不支持或密钥带口令 |
| `TEST_OBSOLETE` | 测试结果已过期 | 测试期间设置被改动，或已有更新的测试登记；该结果**不写入** `lastTest` |
| `NODE_NOT_REGISTERED` | 节点未登记 | `nexa_node` 中无该 `runnerId` |
| `INTERNAL_ERROR` | 未预期错误 | 其余异常，**不返回异常栈** |

`TEST_OBSOLETE` 的后端结果保护已由 R1 落地；前端展示和跨节点异步响应防护仍待 R2 验证。

### 超时与密钥目录

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `flowops.ssh.key-dir` | `/data/flowops/ssh-keys` | 容器内私钥目录；数据库与 API 只保存别名 |
| `flowops.ssh.connect-timeout` | `5s` | TCP + SSH 握手（含主机密钥校验） |
| `flowops.ssh.auth-timeout` | `5s` | 公钥认证 |
| `flowops.ssh.command-timeout` | `5s` | 只读命令 `true` |
| `flowops.ssh.sftp-timeout` | `5s` | SFTP 通道打开 |

只读核查结论（S1 行范围，未修改任何部署文件）：

- `deploy-prod.sh` 以 `-v /data/flowops:/data/flowops` 挂载数据目录，因此宿主机 `/data/flowops/ssh-keys` 在容器内即 `/data/flowops/ssh-keys`，与默认 `key-dir` 一致。
- `Dockerfile` 未声明 `USER`，容器内以 root 运行，可读取宿主机上 `0600` 且属主为 root 的私钥。
- `Dockerfile` 只创建 `/data/flowops/services`、`/data/flowops/logs`，**不创建** `ssh-keys`：该目录由管理员在宿主机创建（`mkdir -p /data/flowops/ssh-keys && chmod 700`），应用不写该目录，缺失时按 `KEY_NOT_FOUND` 返回。
- 密钥文件权限要求 `0600`（或更严），由应用在测试前校验，不满足返回 `KEY_PERMISSION_TOO_OPEN`。

### 安全与边界

- 私钥只按**受限别名**从 `key-dir` 读取：别名白名单 + 目录归一化 + **拒绝符号链接**，并按**真实路径**确认文件落在真实 `key-dir` 内（R3 已修复）；禁止路径分隔符与 `.` / `..`；读取与 `keyFileExists` 使用同一规则，读取时以 `NOFOLLOW_LINKS` 打开。
- 严格主机密钥校验：所选算法与 SHA-256 指纹**双重核对**（H1.3）。先只用所选算法协商一次，确认服务端确实提供它（否则 `HOST_KEY_ALGORITHM_UNAVAILABLE`），再做完整握手并在 verifier 中同时校验算法家族与指纹，任一不符立即中止；不使用"自动信任新密钥"，不读 `known_hosts`。
- 全程**只读**：仅执行 `true` 并打开一次 SFTP 通道，不写远端文件、不安装执行器、不改 `nexa_node` 注册 token、不执行 Docker 操作。
- 日志与响应不出现私钥内容、口令、完整异常栈；主机密钥不匹配时在**服务端日志**记录观察到的指纹（运维可查，不进入 API 响应，避免被误当作可信来源直接回填）。
- 指纹格式与 `ssh-keyscan` / `ssh -o FingerprintHash=sha256` / `ssh-keygen -lf` 输出一致（`SHA256:` + 无填充 base64），已实测核对。
- 首版支持 Linux amd64 目标与公钥认证（RSA / ECDSA / Ed25519；Ed25519 依赖 `net.i2p.crypto:eddsa`）。带口令的私钥首版不支持，按 `KEY_UNREADABLE` 返回。

## 审阅发现的问题与修复顺序（S4 前完成）

| 编号 | 当前代码与触发条件 | 影响 |
| --- | --- | --- |
| R1 | `NodeSshService.test()`先读取 SSH 目标并耗时连接，`persistResult()` 完成后仅按 `runnerId` 更新；测试期间执行 `PUT /ssh`，或同一节点连续发起快慢不同的两次测试 | 旧目标/较早测试的 `CONNECTED` 可覆盖新目标/较新测试的结果。页面会把未验证的新设置显示为连接成功，后续分发阶段可能误用该结论 |
| R2 | `NodeList.tsx` 的 `load()`、保存和测试异步请求没有核对返回时的 `runnerId`；快速关闭 A 节点抽屉并打开 B 节点，A 的请求晚于 B 返回 | A 的设置或测试结果可出现在 B 的抽屉；管理员随后保存会把 A 的 host、指纹等误写到 B 节点 |
| R3 | `resolveKeyFile()`仅检查规范化后的字符串路径；`Files.isRegularFile()`、`Files.newInputStream()` 默认跟随符号链接 | `key-dir` 中的别名若是指向目录外的符号链接，仍可能读取目录外私钥；当前“目录内解析”描述尚未真正保证物理目录边界 |
| R4 | 已新增 `V3_1_4__create_nexa_node_ssh_target.sql`，但当前项目没有自动执行该迁移的配置；真实数据库尚未验证 | 代码和页面测试通过后，生产环境仍可能因缺少 `nexa_node_ssh_target` 表而使 GET/PUT/test 接口失败 |

| 修复顺序 | 项目 / 仓库绝对路径 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R1 | 后端 `D:\project\backend\flowops` | 为 SSH 设置加入单调递增的配置版本；测试开始时在数据库事务内读取不可变设置快照并登记本次测试 ID，测试结束仅在配置版本和最新测试 ID 均匹配时写回。`PUT` 提升版本并清空测试 ID/旧结果；过期测试返回固定 `TEST_OBSOLETE` 结果码，不保存为当前设置的 `lastTest` | S2 | `flowops-app/src/main/java/com/nexa/flowops/service/node/NodeSshService.java`、`SshTestResultCode.java`、SSH 目标 entity/mapper、对应 SQL 迁移与测试 | 当前保存设置的 `lastTest` 只来自该版本最新一次测试 | 用可控的并发顺序验证“测试中改配置”“两次测试后发先至”，确认旧结果不能覆盖 | 不改协议或子节点；不把连接失败改成 API 500 |
| R2 | 前端 `D:\project\front\flowops-front` | 给 SSH 抽屉读取、保存、测试请求加入节点身份和请求序号校验，仅当响应仍属于当前打开的 `runnerId` 时更新表单/结果；切换节点和关闭抽屉时使旧请求失效；加载期间禁用保存和测试；展示 `TEST_OBSOLETE` 原因 | S3、R1 结果码契约 | `src/pages/NodeList.tsx`、`src/pages/NodeList.test.tsx` | 节点切换后表单与结果只属于当前节点 | 用延迟 Promise 固定 A 的读取/保存/测试响应晚于 B 返回，验证 B 表单和保存请求始终属于 B | 不改节点登记 API、后端或其他页面 |
| R3 | 后端 `D:\project\backend\flowops` | 在私钥存在性检查与读取前拒绝符号链接，按真实路径确认文件位于真实 `key-dir` 内，读取与 `keyFileExists` 使用相同规则；仍检查 POSIX 权限 | S2 | `flowops-app/src/main/java/com/nexa/flowops/service/node/NodeSshService.java`、`NodeSshServiceTest.java` | 目录内真实普通私钥可读，符号链接别名不可用 | 在 Linux/POSIX 测试指向目录外和目录内的符号链接均被拒绝，普通 `0600` 文件仍通过 | 不扩大到密钥上传或自动生成 |
| R4 | 后端 `D:\project\backend\flowops`（部署验收） | 在目标数据库执行并核对 `V3_1_4` 与 R1 新增的版本迁移；确认主节点容器可读 `/data/flowops/ssh-keys` 中权限合规的私钥，再从真实节点管理页验证 GET、保存和测试连接 | R1、R2、R3 | `flowops-app/src/main/resources/sql/migration/**`、本文档验收记录；真实环境配置由部署人员执行 | 数据库表、密钥目录与真实 SSH 连接证据 | 正确指纹/密钥成功；错误指纹/密钥失败；普通用户无权限；记录实际主节点与目标宿主机 | 不把单元测试或页面构建当作 S4 完成，不分发/启动子节点 |

R1—R3 是代码修复，R4 是环境验收。协调者复核相应测试与真实 S4 记录后，才可将阶段 1 标记为完成。受派代理只能修改本行仓库和允许范围；跨行改动先反馈协调者。

## 真实联调问题 H1：主机密钥指纹不一致（待排查）

已知事实：目标机 SSH 服务、22 端口、公钥安装和 `runner-1` 登录密钥经管理员确认正常。管理员在**目标机**取得并填入的 ED25519 主机密钥指纹是 `SHA256:Np7pyXIVviX6vYBkmdiiaK9hVY2SL0S4ZpShdVL0EAU`；FlowOps 后端在握手中实际收到的主机密钥指纹是 `SHA256:yTq81R1Jzk/aEQG3T+/1cQq6GBmJ6WS93p5bsCEYMLs`，结果为 `HOST_KEY_MISMATCH`。这两枚指纹不是登录密钥的指纹。不得把后端日志中的“实际”值直接回填表单，也不得关闭主机密钥校验。

当前 `NodeSshService.probe()` 使用 `SshClient.setUpDefaultClient()`，没有指定服务器主机密钥算法；`ServerKeyVerifier` 在握手时对**协商到的** `serverKey` 调用 `KeyUtils.checkFingerPrint(expectedFingerprint, BuiltinDigests.sha256, serverKey)`。因此现有代码确实可能把页面填写的 ED25519 指纹与另一种已协商主机密钥的指纹比较，但目前还**没有**握手算法证据。也必须排查地址/DNS、端口转发、跳板/代理、容器网络所到达的实际主机，以及目标机主机密钥是否变化。

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| H1.1 | 后端 `D:\project\backend\flowops`（联调调查） | 从**目标机可信控制台**逐一记录已启用的 `/etc/ssh/ssh_host_*.pub` 的 SHA-256 指纹；核对 `sshd -T` 的 `hostkey` 与监听地址，并从主节点容器核对连接的目标 IP/端口。给服务端受控诊断日志补充本次握手的主机密钥算法/类型、目标地址与观察到的指纹，关联同一次测试，不记录私钥、公钥内容或认证凭据 | 无 | `NodeSshService.java`、对应测试、本节验收记录；目标机及容器只读诊断 | 明确 `yTq...` 是否属于目标机某把可信主机密钥，并识别实际协商算法和到达的端点 | 用目标机控制台指纹与同次测试日志逐一比对；若均不匹配，停止信任该端点并排查网络/DNS/转发 | 不自动信任实际指纹，不关闭主机校验，不变更目标机主机密钥 |
| H1.2 | 后端 `D:\project\backend\flowops`（契约定稿） | 在 H1.1 证实到达正确目标且确属算法差异后，定稿新增显式 `hostKeyAlgorithm` 字段（`ED25519`、`ECDSA`、`RSA`）与单一 `hostKeySha256` 配对的 API/存储契约；旧记录在补齐算法前不允许继续测试，避免静默假定为 ED25519 | H1.1 | 本文档、SSH DTO/实体/SQL 迁移契约 | 前后端共用的字段、兼容/迁移和校验规则 | 用现有记录及三种主机密钥样本审阅契约 | 不以日志中的观察值自动修改已保存指纹 |
| H1.3 | 后端 `D:\project\backend\flowops` | 按 H1.2 契约限制 SSH 客户端可协商的服务器主机密钥算法，并在 verifier 中同时核对算法与 SHA-256 指纹；算法不匹配或指纹错误时返回 `HOST_KEY_MISMATCH`，服务端不提供所选算法时返回 `HOST_KEY_ALGORITHM_UNAVAILABLE`，保持严格拒绝；补回归测试覆盖目标机同时启用多种 Host Key、正确/错误算法与错误指纹 | H1.2 | `flowops-app` 节点 SSH 服务、DTO/实体/mapper、SQL 迁移及对应测试 | 所选算法与所填指纹指向同一把经管理员核对的主机密钥 | 在多 Host Key 测试服务器上重复连接，确认只协商指定算法且错误值一律失败 | 不修改 Go runner、Nexa Protocol、目标机 SSH 配置或 Docker |
| H1.4 | 前端 `D:\project\front\flowops-front` | SSH 表单增加主机密钥算法选择，并明确提示指纹必须取自**相同算法的目标机主机公钥**；未补齐的旧记录禁止测试，更新类型与页面测试 | H1.2、H1.3 API 定稿 | `src/pages/NodeList.tsx`、`src/api/nodes.ts`、`src/types/**`、相关测试 | 管理员可明确保存算法与对应指纹 | 表单校验、旧记录提示、保存/读取及多算法展示测试 | 不改后端或其他页面 |
| H1.5 | 后端 `D:\project\backend\flowops`（集成验收） | 在 H1.1 确认的真实 Linux 目标机上，从 FlowOps 主节点容器经页面重测；用可信控制台所选算法的指纹取得 `CONNECTED`，再以错误算法/指纹验证拒绝，并记录证据 | H1.3、H1.4、R4 | 本文档验收记录；真实环境由部署人员操作 | 可复核的目标、协商算法、可信指纹与连接结果 | 正确组合成功，错误组合仍为 `HOST_KEY_MISMATCH` 或明确的算法失败码 | 不以仅 TCP 可达或复制日志指纹充当验收 |

H1.1 若发现观察指纹不属于目标机可信主机密钥，先按网络/主机身份异常处理，**不得执行 H1.2—H1.5 的算法方案来掩盖问题**。H1.5 完成后再重新评估 S4；此前本阶段仍为未完成。

### H1.1 客户端侧证据（2026-09-27 执行方记录）

状态：**客户端侧已完成，目标机与容器侧只读诊断待操作者执行；H1 仍未解决。**

在本机用 Apache MINA SSHD 起内嵌服务器，让 `SshClient.setUpDefaultClient()`（与 `NodeSshService.probe()` 同一配置）面对不同主机密钥组合，观察它实际协商到哪一把。
结论已固化为回归测试 `NodeSshServiceTest#verifiesEd25519HostKeyWhenServerAlsoOffersRsa`：

| 服务器启用的主机密钥 | 客户端协商到 | 指纹比对 |
| --- | --- | --- |
| 仅 Ed25519 | `EdDSA/EdDSAPublicKey` | 与 Ed25519 指纹一致 |
| 仅 RSA | `RSA/RSAPublicKeyImpl` | 与 RSA 指纹一致 |
| Ed25519 + RSA（ed25519 在前） | `EdDSA` | 与 Ed25519 指纹一致 |
| Ed25519 + RSA（rsa 在前） | `EdDSA` | 与 Ed25519 指纹一致 |

由此可判定（限于客户端侧）：

- 现有实现**在服务端提供 Ed25519 主机密钥时会协商 Ed25519**，并能正确计算其 SHA-256 指纹；"因为没指定算法而协商到另一把密钥"的假设在本机不成立。
- `HOST_KEY_MISMATCH` 因而更可能来自：目标端点**未提供**该 Ed25519 主机密钥（sshd 有效 `HostKey` / `HostkeyAlgorithms` 限制，或主机密钥已轮换/被替换），或主节点容器到达的**不是**管理员核对的那台主机（地址、DNS、NAT、端口转发、容器网络）。
- 客户端侧证据尚不支持把算法协商当作这次真实故障的已证实根因；即使另行实现 H1.2—H1.3，也**不能代替**目标机与容器的身份核对，更不能据此关闭 H1。
- 附带实现的健壮性：SSHD 无法为 JCA 生成的 Ed25519 公钥计算指纹（`Not an EDDSA public key`），实现已把"无法计算指纹"按校验失败处理（fail closed）并在日志中记录密钥类型。

配套代码改动（H1.1 允许范围）：主机密钥校验的诊断日志现在对**同一次测试**输出 `testId=v<配置版本>s<测试序号>`、`协商算法=<算法/密钥类型>`、`期望指纹`、`观察指纹`，成功时以 INFO 记录所用算法与指纹、不匹配时以 WARN 记录；只记录这些元信息，不记录公钥内容、私钥或认证凭据。

### H1.1 待操作者执行（目标机与主节点容器，只读）

目标机可信控制台（管理员已确认的主机）：

```bash
sudo ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ed25519_key.pub   # 与表单填写的基准比对
sudo ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_rsa_key.pub       # 若存在
sudo ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ecdsa_key.pub     # 若存在
sudo sshd -T | grep -iE '^(hostkey|hostkeyalgorithms|port|listenaddress)'
```

主节点容器内（与后端握手走同一条网络路径，最关键的一步）：

```bash
docker exec flowops sh -lc 'ssh-keyscan -t ed25519,rsa,ecdsa -p <端口> <host> 2>/dev/null | ssh-keygen -lf -'
```

判定：

- 容器看到的指纹集合里**存在**与控制台一致的 `SHA256:Np7pyXIVviX6vYBkmdiiaK9hVY2SL0S4ZpShdVL0EAU` → 端点与密钥一致；用本构建重测，看日志的 `协商算法=` 是否为 EdDSA，并据 H1.1 表格逐项比对。
- 容器看到的指纹集合与**控制台完全不同** → 容器到达了别的主机，按网络/主机身份异常处理（不得回填日志中的观察值）。
- 容器只看到 RSA/ECDSA 而**没有 Ed25519** → 目标机 sshd 未启用该主机密钥或对其做了算法限制；先由管理员修正目标机/服务端配置，再重新核对指纹，而不是改填写值。

### H1.2 契约（已定稿）与 H1.3 实现（2026-09-27 执行方记录）

状态：**H1.2、H1.3 代码与专项测试已完成**（后端）；H1.4 前端代码已存在但测试尚未干净通过；H1.1 目标机/容器核对与 H1.5 真实环境重测未完成，H1 整体仍未关闭。H1.2—H1.3 是新增的显式算法能力，尚无证据证明它解决了原始 `HOST_KEY_MISMATCH`。

H1.2 契约要点（前后端共用）：

| 项 | 约定 |
| --- | --- |
| 新增字段 | 请求与响应均含 `hostKeyAlgorithm`：`ED25519` / `ECDSA` / `RSA`（大小写不敏感，服务端归一化为大写） |
| 配对语义 | `hostKeyAlgorithm` + `hostKeySha256` 指向目标机**同一把**经管理员核对的主机公钥；指纹必须取自该算法对应的 `ssh_host_*_key.pub` |
| 存储 | 迁移 `V3_1_6__add_ssh_target_host_key_algorithm.sql`，`host_key_algorithm VARCHAR(16) NULL` |
| 兼容 | 旧记录为 `NULL`：`GET` 原样返回；`POST .../test` 返回 `HOST_KEY_ALGORITHM_REQUIRED`，**不静默假定**任何算法；无自动改写（不得用日志观察值回填） |
| 校验 | `PUT` 缺少或取值非法 → `code=400`，`msg` 指明 `hostKeyAlgorithm` |

H1.3 实现要点：

- **限制可协商算法**：SSHD 的主机密钥协商与登录认证共用同一套签名工厂，因此采用两阶段握手——
  阶段一只允许所选算法的工厂，用于判定"服务端是否提供该算法"（否 → `HOST_KEY_ALGORITHM_UNAVAILABLE`，
  依据是已完成 SSH 标识交换却收不到主机密钥；连标识都没收到按 `CONNECT_TIMEOUT` 处理）；
  阶段二把所选算法排在最前、并保留登录密钥算法的工厂以免认证失败，协商到所选算法之外时由 verifier 拒绝。
- **算法与指纹双校验**：verifier 先按密钥类型判定算法家族（`ssh-ed25519` / `ssh-rsa` / `ecdsa-sha2-nistp*`），
  再比对 SHA-256 指纹；任一不符返回 `HOST_KEY_MISMATCH`，并在日志给出 `所选算法`、`协商算法`、`密钥类型`、
  `期望指纹`、`观察指纹` 与 `原因`（算法不一致 / 指纹不一致），仍不记录密钥内容。
- 回归测试（`NodeSshServiceTest`，共 33 项，2 项真实符号链接用例需 POSIX）：
  服务端同时启用 Ed25519+RSA 时分别按 ED25519、RSA 配置均 `CONNECTED`；
  服务端只提供 RSA 而选 ED25519 → `HOST_KEY_ALGORITHM_UNAVAILABLE`；
  算法选 ED25519 却填 RSA 指纹 → `HOST_KEY_MISMATCH`；旧记录缺算法 → `HOST_KEY_ALGORITHM_REQUIRED`；
  算法家族判定覆盖 ED25519/ECDSA/RSA 与非法输入；`PUT` 缺少/非法算法 → 400，小写输入归一化为大写。
- H1.4 前端现已加入算法选择、对应主机公钥文件提示和旧记录禁测；其自动化测试须修复下述运行器异常后再确认通过。

### H1 修复复核（2026-09-27）

| 行 | 复核结果 | 尚缺的验收证据 |
| --- | --- | --- |
| H1.1 | 本机多 Host Key 回归用例与后端握手诊断日志已存在；尚无目标机可信控制台与**主节点容器同一路径**取得的主机密钥集合、实际协商算法及端点对照记录 | 确认观察到的 `SHA256:yTq81R1Jzk/aEQG3T+/1cQq6GBmJ6WS93p5bsCEYMLs` 是否属于目标机可信主机密钥，以及为何与管理员核对值不同 |
| H1.2 / H1.3 | 后端字段、迁移 `V3_1_6`、算法限制和算法/指纹双校验均在当前代码中；本次运行 `NodeSshServiceTest` 为 **33 项、0 失败、2 跳过**（Windows 不执行 POSIX 符号链接用例） | 目标环境执行迁移；Linux/CI 补跑跳过用例；真实目标重测 |
| H1.4 | 前端表单、API 类型、旧记录禁测及五项相关测试已写入；`npx tsc -b --pretty false` 通过。定向运行“多算法提示”用例的断言通过，但 Vitest 随后报 `ReferenceError: window is not defined`（页面卸载后的 React 调度任务），**进程退出码为 1**；整页测试运行未正常结束 | 修复测试清理/异步任务问题，完整运行 `NodeList.test.tsx` 并以退出码 0 通过 |
| H1.5 | 未见真实 Linux 目标从 FlowOps 主节点容器经页面重测的 `CONNECTED`、错误指纹/算法拒绝及记录 | 完成 H1.1 身份核对、目标数据库迁移、前端测试后实施真实验收 |

结论：**H1 未修复完毕**。后端新增能力和前端界面不能证明原始指纹不一致已经消失；优先完成 H1.1 的只读对照，若观察值不属于目标机可信主机密钥，按地址、DNS、NAT/转发或主机密钥变更处理，不得通过更换表单指纹绕过校验。
