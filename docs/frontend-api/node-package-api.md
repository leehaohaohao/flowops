# 节点发布包 API（阶段 2，P1 定稿）

> 状态：**P1 契约定稿（2026-09-28）**。契约负责人：协调者（后端仓库 `D:\project\backend\flowops`）。
> 对应实现行：P3（后端 `flowops-app`）、P4（前端 `flowops-front`）。发布包内部格式见 [runner-package-format.md](../runner-package-format.md)；
> 阶段 2 计划与路径/上限规则见 [2026-09-27-runner-package-distribution-plan.md](../2026-09-27-runner-package-distribution-plan.md)。
> 前端**不解析包内容**，只上传文件、展示列表、触发分发、轮询结果。

## 1. 路由与权限

节点身份沿用 `nexa_node.runner_id`（与 [node-api.md](node-api.md)、阶段 1 SSH 接口一致）。
**六个接口全部仅超级管理员**：判定与 `/api/nodes/registry/**` 相同（`StpUtil` 登录态 + `sys_user.is_super_admin = 1`，
后端 `NodeAdminGuard`）。未登录由 Sa-Token 拦截器返回 `code=401`（`msg="未登录或登录已过期"`）；
已登录但非超管，**六个接口统一返回** `code=403`、`msg="仅超级管理员可管理节点发布包"`。前端隐藏入口不作为授权依据。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/nodes/packages` | 上传发布包（`multipart/form-data`，字段名 `file`） |
| GET | `/api/nodes/packages` | 主节点已存储的发布包列表（按上传时间倒序） |
| GET | `/api/nodes/packages/{sha256}` | 单个发布包详情 |
| POST | `/api/nodes/registry/{runnerId}/packages/{sha256}/distribute` | 触发一次分发（**异步**，立即返回记录） |
| GET | `/api/nodes/registry/{runnerId}/packages` | 该节点各包的最新一次分发记录（按记录 id 倒序） |
| GET | `/api/nodes/registry/{runnerId}/packages/distributions/{id}` | 轮询单条分发记录 |

路由注意（P3 实现时不要踩）：

- `GET /api/nodes/packages` 与既有 `GET /api/nodes/{runnerId}` 同前缀。Spring 的路径匹配优先选择**字面量**模式，
  因此 `/api/nodes/packages` 不会被 `/{runnerId}` 抢走（与既有 `/api/nodes/registry` 的共存方式相同）。
- `GET .../packages/distributions/{id}` 与 `POST .../packages/{sha256}/distribute` 段数不同，不会互相匹配。
- 所有响应沿用既有 `Result<T>`：`{code, msg, data}`；**HTTP 状态码恒为 200**，业务结果看 `code`。

`sha256` 路径参数规则：必须为 64 位小写十六进制；不合法返回 `code=400`，`msg="sha256 格式非法"`；
合法但不存在返回 `code=404`，`msg="发布包不存在: <sha256>"`。

## 2. 上传发布包

```
POST /api/nodes/packages
Content-Type: multipart/form-data

file=<flowops-executor-<version>-linux-amd64.tar.gz>
```

- 只接受单个文件字段 `file`；其他字段忽略。文件上限 **1 GiB（1 073 741 824 字节）**，边读边计数，超限即中止。
- 服务端按 [runner-package-format.md](../runner-package-format.md) §7 的单遍流式流程校验：文件名 → 大小 → 逐成员摘要与白名单 →
  `manifest.json` → `checksums.txt` → 交叉校验 → 原子入存储目录。**不解压落盘、不执行包内任何内容**。
- 成功 `data` 为 `RunnerPackageVO`（§3.1）。

成功响应（新包）：

```jsonc
{
  "code": 200,
  "msg": "发布包已上传",
  "data": {
    "sha256": "3f2c8a1d9e4b7c6f5a0d3e2b1c4f7a8d9e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d",
    "fileName": "flowops-executor-0.7.0-linux-amd64.tar.gz",
    "version": "0.7.0",
    "os": "linux",
    "arch": "amd64",
    "sizeBytes": 268435456,
    "gitCommit": "4786ea0020f471c6ddf53ebef098490ad7f641b6",
    "imageReference": "flowops-executor:0.7.0",
    "imageId": "sha256:9f2c1e0b...",
    "dockerCliVersion": "27.3.1",
    "composeVersion": "v2.29.7",
    "formatVersion": 1,
    "uploadedBy": "admin",
    "uploadedAt": "2026-09-28T10:12:33",
    "existing": false
  }
}
```

成功响应（相同摘要已存在）：`code=200`，`msg="发布包已存在（相同摘要）"`，`data.existing=true`，其余字段取自既有记录。

上传失败（`code=400`，`msg` 为下表固定文案，前端直接展示 `msg`）：

| 触发条件 | `msg` |
| --- | --- |
| 缺少 `file` 字段或文件为空 | `发布包文件不能为空` |
| 超过 1 GiB | `发布包超过 1 GiB 上限` |
| 文件名不符合命名规则 | `发布包文件名应为 flowops-executor-<版本>-linux-amd64.tar.gz` |
| 文件名版本与 manifest 不一致 | `发布包文件名与 manifest 版本不一致` |
| `os`/`arch` 非 linux/amd64 | `发布包平台不受支持（仅 linux/amd64）` |
| `packageFormatVersion` 非 1 | `发布包格式版本不受支持` |
| manifest 字段缺失/类型错/取值非法 | `发布包 manifest 不合法: <字段名>` |
| 归档成员超出白名单、重名、非普通文件、越界路径 | `发布包成员不合法: <成员路径>` |
| 成员大小/数量/解压总计超上限 | `发布包成员超出大小上限` |
| `checksums.txt` 缺失、行格式错或路径集合不符 | `发布包校验清单不合法` |
| 成员摘要与 `checksums.txt` 不一致 | `发布包成员摘要不一致: <成员路径>` |
| 存储目录不可写/不可创建 | `发布包存储不可用，请检查主节点磁盘与权限` |

其他：非超管 `code=403`，`msg="仅超级管理员可管理节点发布包"`。

## 3. 查询发布包

### 3.1 `RunnerPackageVO`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sha256` | string | 包摘要（小写十六进制 64 位），不可变标识 |
| `fileName` | string | 上传时的文件名 |
| `version` | string | manifest 版本 |
| `os` / `arch` | string | 固定 `linux` / `amd64` |
| `sizeBytes` | number | `.tar.gz` 字节数 |
| `gitCommit` | string | 构建提交 |
| `imageReference` | string | 镜像引用（如 `flowops-executor:0.7.0`） |
| `imageId` | string | 镜像配置摘要（`sha256:...`） |
| `dockerCliVersion` / `composeVersion` | string | 镜像内 CLI / Compose 版本 |
| `formatVersion` | number | `packageFormatVersion` |
| `uploadedBy` | string | 上传者用户名 |
| `uploadedAt` | string | 上传时间（`2026-09-28T10:12:33`，本地时间） |
| `existing` | boolean | 仅上传响应出现；`true` 表示同摘要已存在 |

**不返回**主节点存储绝对路径、临时文件名、私钥路径等服务器端信息（与阶段 1 脱敏先例一致）。

### 3.2 列表

```
GET /api/nodes/packages        → Result<List<RunnerPackageVO>>   （按 uploadedAt 倒序；空列表返回 []）
GET /api/nodes/packages/{sha256} → Result<RunnerPackageVO>        （不存在 404）
```

## 4. 分发发布包（异步）

```
POST /api/nodes/registry/{runnerId}/packages/{sha256}/distribute
```

### 4.1 前置条件（不满足即拒绝，不创建记录）

| 条件 | 不满足时的响应 |
| --- | --- |
| 节点已登记（`nexa_node` 存在该 `runnerId`） | `code=404`，`msg="节点未登记: <runnerId>"` |
| 发布包已存储（§3.1 存在） | `code=404`，`msg="发布包不存在: <sha256>"` |
| 该节点已配置 SSH 目标 | `code=400`，`msg="尚未配置 SSH 目标"` |
| SSH 目标已补齐 `hostKeyAlgorithm`（阶段 1 H1.2） | `code=400`，`msg="尚未选择主机密钥算法，请先补全 SSH 设置"` |
| SSH 最近一次测试为 `CONNECTED`（阶段 1 `lastTest`，受 R1 版本保护） | `code=400`，`msg="该节点 SSH 尚未通过连接测试，请先测试连接"` |

> 分发**不重新探测**连接：它直接使用已保存的 SSH 设置建立新会话，并复用阶段 1 的严格主机密钥校验
> （算法 + 指纹双校验）、私钥别名解析与只读边界。修改 SSH 设置会清空 `lastTest`，因此上面的"已通过测试"
> 永远属于**当前**设置。详见阶段 2 计划 §P1 契约中"与阶段 1 对齐"一节。

### 4.2 幂等与并发

判定顺序固定为：**前置条件（§4.1）→ 同摘要进行中（返回既有记录）→ 该节点其他摘要在途（409）→ 全局队列已满（409）→ 新建记录**，
保证重复点击不产生第二条记录、也不会因为队列判满而误报。

| 情形 | 行为 |
| --- | --- |
| 该节点该摘要已有**进行中**记录（`PENDING`/`UPLOADING`/`VERIFYING`） | `code=200`，返回既有记录，`msg="该分发正在进行中"`；**不新建记录、不新建 SSH 会话**（用于前端重复点击） |
| 该节点有**另一个摘要**正在进行中 | `code=409`，`msg="该节点已有分发任务进行中"` |
| 全局并发分发已达上限 | `code=409`，`msg="分发队列已满，请稍后重试"` |
| 该节点该摘要已有终态记录（`SUCCEEDED`/`FAILED`） | 新建一次记录并重新执行（允许失败后重试） |
| 目标机已存在同摘要正式包且远端摘要一致 | `SUCCEEDED`，`alreadyPresent=true`（**视为成功**，不重复传输） |

### 4.3 响应

立即返回（`code=200`），`data` 为 `PackageDistributionVO`（§5.1），此时 `status` 通常为 `PENDING`。
分发在后台执行；前端按下表轮询。

## 5. 分发记录查询

### 5.1 `PackageDistributionVO`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | 记录 id（轮询用） |
| `runnerId` | string | 目标节点 |
| `packageSha256` | string | 包摘要 |
| `version` / `fileName` | string | 包版本与文件名（便于列表展示，避免二次查询） |
| `sizeBytes` | number | 包大小 |
| `status` | string | `PENDING` / `UPLOADING` / `VERIFYING` / `SUCCEEDED` / `FAILED` |
| `alreadyPresent` | boolean | 本次是否因目标机已有同摘要包而跳过传输即成功 |
| `errorCode` | string \| null | 失败时的机器码（§5.4）；成功为 `null` |
| `errorMessage` | string \| null | 失败时的固定中文说明，可**直接展示** |
| `remotePath` | string \| null | 目标机正式包路径 `/opt/flowops/runner/packages/<sha256>.tar.gz`（固定、非敏感） |
| `operator` | string | 触发分发的超级管理员 |
| `startedAt` | string \| null | 开始执行时间（创建时即写入） |
| `finishedAt` | string \| null | 终态时间 |
| `durationMs` | number \| null | 终态耗时 |

### 5.2 列表与单条

```
GET /api/nodes/registry/{runnerId}/packages                        → Result<List<PackageDistributionVO>>
GET /api/nodes/registry/{runnerId}/packages/distributions/{id}    → Result<PackageDistributionVO>
```

- 列表：每个 `packageSha256` 只返回该节点**最新一次**记录，按 `id` 倒序；节点未登记 404。用于节点管理页"该节点已分发了哪些包、结果如何"。
- 单条：`id` 不存在或不属于该 `runnerId` 时 `code=404`，`msg="分发记录不存在: <id>"`。

### 5.3 状态机

```text
PENDING ──► UPLOADING ──► VERIFYING ──► SUCCEEDED
   │            │              │
   └────────────┴──────────────┴──► FAILED（带 errorCode）
```

- 终态只有 `SUCCEEDED` / `FAILED`；`alreadyPresent=true` 时跳过 `UPLOADING`（`PENDING` → `VERIFYING` → `SUCCEEDED`），因为该判定在已建会话后对远端正式文件计算摘要得出。
- **不提供取消、暂停、字节级进度**（v1 明确不做）。前端按状态做**阶段式**进度展示。
- 主节点在分发中途重启时，未完成记录统一置 `FAILED` + `MASTER_RESTARTED`；目标机遗留的 `<sha256>.part` 由下一次分发覆盖前清理。

### 5.4 `errorCode` 与中文说明（前端按此映射；`errorMessage` 已给中文，二者取其一）

连接阶段的结果码**与阶段 1 `SshTestResultCode` 同名同义（无前缀）**，前端可直接复用既有中文映射；分发特有失败使用 `REMOTE_*` / `UPLOAD_*` 前缀：

| `errorCode` | 含义 |
| --- | --- |
| `CONNECT_TIMEOUT` / `CONNECT_FAILED` | 建立连接超时 / 无法连接（端口、地址、路由） |
| `HOST_KEY_MISMATCH` | 主机密钥不匹配（算法或指纹），已中止，未写任何文件 |
| `HOST_KEY_ALGORITHM_UNAVAILABLE` | 目标机未提供所选主机密钥算法 |
| `AUTH_TIMEOUT` / `AUTH_FAILED` | 认证超时 / 公钥认证失败 |
| `SFTP_TIMEOUT` / `SFTP_FAILED` | SFTP 通道超时 / 不可用 |
| `KEY_NOT_FOUND` / `KEY_PERMISSION_TOO_OPEN` / `KEY_ALIAS_INVALID` / `KEY_UNREADABLE` | 私钥缺失 / 权限过宽 / 别名非法 / 不可读 |
| `REMOTE_DIR_MISSING` | 目标机固定目录不存在（需运维预置） |
| `REMOTE_DIR_NOT_WRITABLE` | 目标机目录当前账号不可写 |
| `REMOTE_DISK_INSUFFICIENT` | 目标机可用空间不足 |
| `REMOTE_WRITE_FAILED` | 写入临时文件失败（含传输中被拒绝） |
| `REMOTE_TOOL_MISSING` | 目标机缺少 `sha256sum`/`mv`/`stat`/`rm` 等基础命令 |
| `REMOTE_COMMAND_TIMEOUT` / `REMOTE_COMMAND_FAILED` | 远端校验/改名命令超时 / 退出码非 0 |
| `REMOTE_CHECKSUM_MISMATCH` | 远端临时文件摘要与包摘要不一致（传输损坏）：不改名为正式包，删除临时文件 |
| `UPLOAD_TIMEOUT` / `UPLOAD_INTERRUPTED` | 整体传输超时 / 连接中断（含 SFTP 会话断开） |
| `MASTER_RESTARTED` | 主节点在分发过程中重启 |
| `STORE_READ_FAILED` | 主节点读取已存储包失败 |
| `INTERNAL_ERROR` | 未预期错误（服务端日志有详情，API 不含异常栈） |

远端**不返回**命令输出或异常栈；`remotePath` 是唯一返回的远端路径。

## 6. 前端（P4）实现注意

1. **上传超时**：全局 axios 实例 `timeout: 100000`（100 秒），1 GiB 上传会超时。上传与分发请求必须单独覆盖超时
   （如 `timeout: 0` 或 ≥ 30 分钟），并让全局拦截器的 `code` 语义继续生效。
2. **multipart 写法**：沿用既有 `uploadJar` 模式（`FormData` + `form.append('file', file)`），不要手工拼 boundary。
3. **上传前本地预检**：文件名为 `flowops-executor-<版本>-linux-amd64.tar.gz` 且 ≤ 1 GiB 时再提交，减少无效传输；
   服务端仍会完整校验，前端预检不作为安全边界。
4. **轮询**：分发接口立即返回记录，用 `GET .../packages/distributions/{id}` 轮询（建议 2 秒间隔，上限 30 分钟对齐服务端
   传输超时）；进入 `SUCCEEDED`/`FAILED` 即停止；离开页面时停止轮询。
5. **重复点击**：按钮在请求进行中禁用；即使重复点击，服务端对"进行中的同摘要分发"返回既有记录（不会重复传输）。
6. **权限**：仅超管显示上传与分发入口（`GET /auth/info` 的 `data.superAdmin`），后端仍独立判定 403。
7. **前置提示**：分发按钮应在节点 SSH 未配置/未通过测试时给出明确提示（对应 §4.1 的 400 文案），不要静默失败。
8. **不做**：不解析包内容、不提供解压/启动/停止入口、不展示字节级进度（阶段 2 范围外）。

## 7. 服务端配置项（P3 实现，供联调核对）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `flowops.runner-package.store-dir` | `/data/flowops/runner-packages` | 主节点不可变存储目录（应用自行创建；`deploy-prod.sh` 已把 `/data/flowops` 挂进容器） |
| `flowops.runner-package.remote-dir` | `/opt/flowops/runner/packages` | 目标机固定目录（由 SSH 管理账号**预先创建**，应用不创建） |
| `flowops.runner-package.max-size` | `1GB` | 压缩包上限（1 GiB） |
| `flowops.runner-package.upload-timeout` | `30m` | 单次传输整体超时 |
| `flowops.runner-package.remote-command-timeout` | `5m` | 远端单条命令超时（`sha256sum --`、`df -Pk --`、`mv -f --`） |
| `flowops.runner-package.remote-free-space-margin` | `64MB` | 远端空间预检余量（可用空间 < 包大小 + 余量即拒绝） |
| `flowops.runner-package.max-concurrent-distributions` | `2` | 全局并发分发上限（同一节点始终串行） |
| `flowops.runner-package.distribution-queue-capacity` | `32` | 等待执行的分发队列容量；超出返回 409 |
| `flowops.runner-package.max-member-size` | `2GB` | 结构上限：单个成员解压后大小 |
| `flowops.runner-package.max-total-uncompressed-size` | `4GB` | 结构上限：全部成员解压后总计 |
| `flowops.runner-package.max-entries` | `64` | 结构上限：归档成员数（格式 v1 实际为 6） |
| `flowops.ssh.*` | 阶段 1 值 | 连接/认证/SFTP 通道超时复用阶段 1 契约 |

上传容量还需（P3 行）调整全局 multipart 限制，使其**大于** 1 GiB 上限，保证超限由应用返回统一文案而不是框架异常：

```yaml
spring.servlet.multipart.max-file-size: 1280MB
spring.servlet.multipart.max-request-size: 1280MB
server.tomcat.max-swallow-size: 1280MB
```

（当前为 500MB，不足以上传 1 GiB 包。）

## 8. 2026-09-28 修订（D0 契约）：SSH 配置版本绑定与存储失同步

> 本节是[阶段 2 发布包设计审阅](../issues/2026-09-28-phase-2-runner-package-design-review.md) **D0** 行的契约定稿，
> 细化 R1（分发绑定已验证 SSH 配置版本）与 R2（索引与本地文件一致性）。**未修改任何业务代码**；
> B1／B2 按本节实现后，前端的展示与提示以本节为准。上文 §1—§7 保持原样，冲突处以本节为准。

### 8.1 新增字段

| 接口 | 字段 | 类型 | 说明 |
| --- | --- | --- | --- |
| 上传响应 `RunnerPackageVO` | `repaired` | boolean（仅上传响应出现） | 本次请求是否**重写了主节点磁盘上的包文件**（文件缺失/损坏时按摘要修复） |
| 分发记录 `PackageDistributionVO` | `sshConfigVersion` | number \| null | 本次分发绑定的、**已通过连接测试**的 SSH 配置版本（阶段 1 `nexa_node_ssh_target.config_version`）；`null` = 未绑定（旧记录，分发必然失败为 `SSH_CONFIG_CHANGED`） |

`existing` 语义保持不变：**索引行此前已存在**（不代表"什么都没做"，见 8.2）。

### 8.2 上传成功响应的五种分支（`code=200`）

| 分支 | `existing` | `repaired` | `msg` |
| --- | --- | --- | --- |
| 全新上传 | `false` | `false` | `发布包已上传` |
| 已有索引 + 磁盘文件健康（大小与摘要一致） | `true` | `false` | `发布包已存在（相同摘要）` |
| 已有索引 + 文件缺失/损坏，本次按摘要修复 | `true` | `true` | `发布包已存在，已按摘要修复存储文件` |
| 无索引 + 同名文件已存在且内容复核等于摘要（补登记） | `false` | `false` | `发布包已补登记（文件已存在且摘要一致）` |
| 无索引 + 同名文件内容不等于摘要（按损坏替换） | `false` | `true` | `发布包已上传` |

**前端展示建议**：`repaired=true` 属于"自愈"结果，成功提示可附带"已修复存储文件"，不必当作错误；其余按 `msg` 直接展示。

### 8.3 新增失败错误码（追加到 §5.4 的分发记录表）

| `errorCode` | 含义与前端提示 |
| --- | --- |
| `SSH_CONFIG_CHANGED` | SSH 设置已变更或已被删除，需重新配置并测试连接后再分发（提示用户回到该节点执行"测试连接"后再分发） |
| `SSH_NOT_VERIFIED` | 该节点 SSH 最近一次测试未通过，请重新测试连接后再分发 |
| `STORE_CHECKSUM_MISMATCH` | 主节点存储的发布包与摘要不一致，请重新上传该包（提示用户重新上传同版本包；无需改节点设置） |

三者都在**建立远端会话之前**产生：目标机不会有任何新文件或残留临时文件，`remotePath` 仍为固定目标路径。
阶段 3 安装门禁另有一个错误码 `TEMPLATE_INCOMPATIBLE`（「该发布包的启动模板不符合节点凭据读取约定，不能安装」），
出现在安装作业接口，不出现在本节的阶段 2 分发接口。

### 8.4 与分发前置的关系（请求级 400 不变）

`POST .../distribute` 在**排队时**仍按 §4.1 校验：节点已登记、包已存储、SSH 目标存在、`hostKeyAlgorithm` 已补齐、
`lastTest.resultCode == CONNECTED`，并把当时通过的 `config_version` 绑定到记录上。
排队之后设置被修改（或重测失败、目标被删除）时，不再返回请求级错误，而是在记录上落 `FAILED` +
`SSH_CONFIG_CHANGED` / `SSH_NOT_VERIFIED`。前端因此需要**同时**处理"提交时的 400 提示"与"轮询到的失败码"。

### 8.5 轮询与重试（前端行为）

1. 轮询单条记录（§5.2、§6.4）时同时展示 `status`、`errorCode`/`errorMessage`、`sshConfigVersion`；
2. `FAILED` 且错误码属于 §8.3 时，页面直接给出对应操作入口（去测连接 / 重新上传），不要显示"重试分发"；
3. 其余失败码沿用 §5.4 既有映射与"重试分发"入口（服务端允许对终态记录重新发起分发）；
4. 分发接口的请求级 409（同节点在途、队列已满）仍按 §4.2 处理，不要与运行期失败码混用。

### 8.6 不改动的内容

- 六个接口的路径、权限（仅超级管理员）、`Result<T>` 信封与 HTTP 200 约定不变；
- 上传大小上限（1 GiB）与 multipart 1280MB 设置不变；
- 包格式仍为 `packageFormatVersion = 1`（六成员、manifest 字段集不变，见[格式契约 §13](../runner-package-format.md)）；
- 运行期失败码不会作为请求级响应返回（请求级仍是 `code=400/403/404/409` + 固定 `msg`）。
