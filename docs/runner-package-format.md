# 子节点执行器发布包格式契约（packageFormatVersion 1）

> 状态：**P1 定稿（2026-09-28）**。适用于[阶段 2：通过 SSH 分发子节点执行器发布包](2026-09-27-runner-package-distribution-plan.md)的 P2/P3/P5，
> 以及[阶段 3：双模式启动](2026-09-27-runner-start-and-node-operations-plan.md)的 O1/O2 解包消费。
> 定稿人：协调者（后端仓库 `D:\project\backend\flowops`）。**本文件是发布包格式的唯一权威来源**；
> 前端（`D:\project\front\flowops-front`）不解析包内容，只处理主节点 API，见 [node-package-api.md](frontend-api/node-package-api.md)。

本文件只定义**契约**，不含构建脚本与传输代码。包由执行器仓库（`D:\project\go\flowops-executor`，P2 行）产出；
主节点（`flowops-app`，P3 行）接收、校验、存储并分发。

## 1. 身份、命名与大小上限

| 项 | 约定 |
| --- | --- |
| 文件名 | `flowops-executor-<version>-linux-amd64.tar.gz`，区分大小写，无路径前缀 |
| `<version>` | `^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$`（SemVer 主.次.修订，可选预发布段；不写 `v` 前缀、不使用 `+` 构建元数据） |
| 包身份 | 对 `.tar.gz` **文件字节**计算的 SHA-256，小写十六进制 64 字符。主节点以该摘要作为不可变标识，也是远端正式文件名 |
| `platform` | 仅 `linux` / `amd64`。其他平台值一律拒绝（阶段 1 同样只支持 Linux amd64 目标） |
| 压缩包大小上限 | **1 GiB = 1 073 741 824 字节**（压缩后）。超过即拒绝，不做截断 |
| 归档格式 | 外层 gzip（RFC 1952）单流，内层 tar（POSIX ustar 或 PAX） |
| 两种运行模式 | **同一个包同时支持 `native` 与 `container`**，包含二进制与容器镜像 tar。不支持"按模式分别打包" |
| 版本权威来源 | `manifest.json` 的 `package.version`。文件名中的 `<version>` 必须与之完全相同；执行器自身上报的 `runner.version` 也必须等于它（见 §8） |

包内**不允许**出现 runnerId、主节点地址、注册 token、SSH 私钥/公钥、目标机路径配置、任何环境专属取值，见 §6。

## 2. 包内布局（格式 v1 固定成员，成员数必须恰好为 6）

```text
flowops-executor-<version>-linux-amd64.tar.gz
├── manifest.json                          # 0644，UTF-8 无 BOM，LF
├── checksums.txt                          # 0644，UTF-8 无 BOM，LF
├── bin/
│   └── flowops-executor                   # 0755，Linux amd64 静态可执行文件
├── images/
│   └── flowops-executor.tar               # 0644，docker save 产物（linux/amd64）
└── templates/
    ├── flowops-executor.service           # 0644，systemd 模板（无值模板，§5.4）
    └── docker-compose.yaml                # 0644，Compose 模板（无值模板，§5.4）
```

规则：

- **没有顶层包装目录**：成员路径就是上表的相对路径，`manifest.json` 与 `checksums.txt` 在归档根。
- 只有**普通文件**：不允许目录项、符号链接、硬链接、设备/FIFO 等任何特殊成员。
- 成员路径只能使用 `[A-Za-z0-9._/-]`，不含前导 `/`、不含 `..`、不含反斜杠、不含空白；长度 ≤ 200。
- 权限位固定为上表取值，归档必须保留（主节点会按表校验 mode）。
- 格式 v1 **不接受新增成员**。需要增加成员（例如自带启动脚本）时必须提升 `packageFormatVersion` 并先修订本文件（§10）。

结构上限（防解压炸弹与资源耗尽，主节点在只读遍历时强制）：

| 项 | 上限 |
| --- | --- |
| 归档成员数 | ≤ 64（v1 实际为 6） |
| 单个成员解压后大小 | ≤ 2 GiB |
| 全部成员解压后总计 | ≤ 4 GiB |

## 3. `manifest.json`

UTF-8、无 BOM、LF，单个 JSON 对象。字段名与结构如下（示例值仅示意）：

```json
{
  "packageFormatVersion": 1,
  "package": {
    "name": "flowops-executor",
    "version": "0.7.0",
    "os": "linux",
    "arch": "amd64",
    "fileName": "flowops-executor-0.7.0-linux-amd64.tar.gz",
    "gitCommit": "4786ea0020f471c6ddf53ebef098490ad7f641b6",
    "sourceDateEpoch": 1758931200
  },
  "binary": {
    "path": "bin/flowops-executor",
    "goVersion": "go1.26.3"
  },
  "image": {
    "path": "images/flowops-executor.tar",
    "reference": "flowops-executor:0.7.0",
    "imageId": "sha256:9f2c1e0b7a4d5c8e6f3a2b1d0c9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e",
    "dockerCliVersion": "27.3.1",
    "composeVersion": "v2.29.7"
  },
  "templates": {
    "systemd": "templates/flowops-executor.service",
    "compose": "templates/docker-compose.yaml"
  },
  "members": [
    { "path": "bin/flowops-executor", "size": 18456789, "mode": "0755" },
    { "path": "images/flowops-executor.tar", "size": 268435456, "mode": "0644" },
    { "path": "templates/docker-compose.yaml", "size": 812, "mode": "0644" },
    { "path": "templates/flowops-executor.service", "size": 634, "mode": "0644" }
  ]
}
```

字段规则：

| 字段 | 必填 | 类型 / 取值 | 校验 |
| --- | --- | --- | --- |
| `packageFormatVersion` | 是 | 整数 | 必须 `= 1`，否则主节点拒绝（`MANIFEST_FORMAT_UNSUPPORTED`） |
| `package.name` | 是 | 字符串 | 必须 `= "flowops-executor"`（保留字段，便于将来多产物） |
| `package.version` | 是 | 字符串 | 匹配 §1 版本正则，且与文件名中的 `<version>` 相同 |
| `package.os` | 是 | 字符串 | 必须 `= "linux"` |
| `package.arch` | 是 | 字符串 | 必须 `= "amd64"` |
| `package.fileName` | 是 | 字符串 | 必须等于实际上传文件名（同时等于 `flowops-executor-<version>-linux-amd64.tar.gz`） |
| `package.gitCommit` | 是 | 40 位小写十六进制 | 构建所用提交；`0000…0` 表示本地脏构建，主节点接受但记录 |
| `package.sourceDateEpoch` | 是 | 整数（Unix 秒） | 归档内所有成员的 `mtime`，默认取提交时间（§5.2） |
| `binary.path` | 是 | 字符串 | 必须 `= "bin/flowops-executor"` |
| `binary.goVersion` | 是 | 字符串 | 形如 `go1.26.3`；仅记录，不参与判定 |
| `image.path` | 是 | 字符串 | 必须 `= "images/flowops-executor.tar"` |
| `image.reference` | 是 | 字符串 | 形如 `flowops-executor:<version>`；阶段 3 用它 `docker load` 后启动 |
| `image.imageId` | 是 | `sha256:` + 64 位小写十六进制 | `docker load` 后 `docker image inspect --format '{{.Id}}'` 必须与之相等（阶段 3 校验） |
| `image.dockerCliVersion` | 是 | 非空字符串 | 镜像内 `docker --version` 报告的版本；主节点只校验非空 |
| `image.composeVersion` | 是 | 非空字符串 | 镜像内 `docker compose version` 报告的版本；主节点只校验非空 |
| `templates.systemd` | 是 | 字符串 | 必须 `= "templates/flowops-executor.service"` |
| `templates.compose` | 是 | 字符串 | 必须 `= "templates/docker-compose.yaml"` |
| `members[]` | 是 | 数组，元素 `{path,size,mode}` | 必须**恰好**列出 §2 的 4 个载荷成员，按 `path` 升序；`size` 为字节数，`mode` 为 4 位八进制字符串 |

补充规则：

- `manifest.json` 自身**不包含**任何摘要字段，也不包含 `checksums.txt` 的摘要——避免循环。成员摘要的唯一来源是 `checksums.txt`。
- 时间字段只允许 `sourceDateEpoch`。**不得**写入构建墙钟时间（否则同一提交无法重复构建出同一摘要，见 §5.2）。
- 未知字段：主节点忽略（向前兼容读取），但 P2 不得依赖此行为，新增字段仍需提升格式版本。

## 4. `checksums.txt`

`sha256sum` 兼容格式，UTF-8、无 BOM、**LF** 行尾，每行：

```text
<64位小写十六进制 sha256><两个空格><成员相对路径>
```

（即 `<hex>` + `"  "` + `<path>`；不使用 `*` 二进制标记模式。）

内容规则：

- 覆盖 **§2 的 4 个载荷成员 + `manifest.json`，共 5 行**；**不包含** `checksums.txt` 自身。
- 按 `path` 升序（与 tar 成员顺序一致），无空行、无注释、无重复、无多余条目。
- 每行以单个 LF 结束（**最后一行也必须有结尾 LF**）；不得出现 CR、空行或行末空白。
- 每行路径必须与 §2 的允许集合一致；路径字符集同 §2。

主节点据此逐成员比对摘要；`checksums.txt` 与 tar 实际成员集合或摘要不符即拒绝（`CHECKSUM_MISMATCH`）。

## 5. 归档与确定性构建要求（P2 行）

### 5.1 tar

- 成员顺序：按 `path` 字节序升序（与 `checksums.txt` 顺序一致）。
- `uid = gid = 0`，`uname`/`gname` 置空。
- 所有成员 `mtime = package.sourceDateEpoch`。
- 权限位严格取 §2 固定值；保留执行位。
- 不写入标准 PAX 头之外的私有/厂商字段；同一输入必须产生相同的头字节。

### 5.2 可重复构建

- 打包步骤必须**确定性**：相同源码提交 + 相同 `images/flowops-executor.tar` + 相同打包工具链（tar/gzip 实现版本）重建，必须得到**逐字节相同**的 `.tar.gz` 与**相同 SHA-256**。
- 因此：打包步骤消费**已构建好的镜像 tar**（镜像 tar 由独立步骤产出，自身可以不满足字节级可重复），并以 `sourceDateEpoch`（默认 `git log -1 --format=%ct`）替代墙钟时间。
- gzip 必须单流、固定压缩级别、**不写入原始文件名与时间戳**（等价 `gzip -n`）。
- P2 验收方式：同一提交连续构建两次并比对 `.tar.gz` 的 SHA-256（必须相同）。工具链不同导致镜像内容不同时，摘要可以不同——此时是两个不同的包，不是构建失败。

### 5.3 归档内不得出现的路径特征

主节点在遍历时拒绝以下任何一项（fail closed，且不落盘任何解压内容）：

- 绝对路径、以 `/` 开头、含 `..` 段、含反斜杠；
- 非普通文件成员（目录、符号链接、硬链接、设备、FIFO）；
- 重名成员、需要覆盖已存在成员的成员；
- §2 白名单之外的成员、缺失必需成员；
- 超过 §2 结构上限（成员数/单成员/总计）。

### 5.4 模板（`templates/*`）约束

- 模板是**无值模板**：只允许出现固定路径、变量引用与进程/容器管理配置（重启策略、资源名、挂载、健康检查等）。
- **不得**包含 token 明文、runnerId 值、主节点地址值、目标机专属取值；变量名只能取自执行器既有的环境变量词汇：
  `APP_ENV`、`FLOWOPS_RUNNER_ID`、`FLOWOPS_MASTER_ADDR`、`FLOWOPS_RUNNER_TOKEN`、`FLOWOPS_RUNNER_VERSION`。
- 模板的**确切内容**（环境文件位置、挂载、重启策略、资源名称）由**阶段 3 O2 行**定稿；P1 只固定文件路径、无值约束与变量词汇。

## 6. 无密钥与无环境配置约束（P2 行必须自证）

包内**不得**包含：注册 token（含其哈希以外的任何形式）、runnerId 取值、主节点地址取值、
SSH 私钥或公钥、外部 YAML 配置中的环境取值、数据库口令。

执行器的内嵌配置（`config/config.{prod,dev}.yaml`，经 `go:embed` 编译进二进制）必须满足：

- `runner.id`、`runner.master_addr`、`runner.token` 三项**留空**；
- 只允许内嵌非敏感项（超时、日志、`runner.version` 等）。

P2 的验收证据（必须写进 P2 交付说明）：

1. 以**空配置**启动二进制（不给 CLI/ENV/`FLOWOPS_CONFIG`）时，必须因缺少 `runner.id` / `runner.master_addr` / `runner.token` 直接退出，
   而不是尝试连接任何主节点——这证明包内没有可用凭据。
2. 按实际 token（若构建机环境存在）在二进制中做字符串检索，不得命中；检索方式与结果记录在 P2 交付说明中（不得记录 token 本身）。

## 7. 主节点校验流程（P3 行实现，顺序固定）

上传的单次流式读取中依次完成；**任何一步失败都不得留下可用包**：

1. 文件名匹配 §1 命名规则（廉价检查，先失败先返回）。
2. 压缩包字节数上限 1 GiB（边读边计数，超限即中止，不依赖 `Content-Length`）。
3. 单遍流式：边读边算 `.tar.gz` 的 SHA-256、边解析 gzip/tar，同时写入存储目录的临时文件；**不得**整份缓冲进内存。
4. 遍历成员时执行 §5.3 的全部拒绝规则，并按 §2 上限限制成员数与解压大小；成员内容**只用于计算摘要，不落盘、不执行**。
5. 解析 `manifest.json`，按 §3 校验格式版本与每个字段；拒绝 `MANIFEST_FORMAT_UNSUPPORTED` / `MANIFEST_INVALID` / `PACKAGE_PLATFORM_UNSUPPORTED` / `PACKAGE_NAME_MISMATCH`。
6. 解析 `checksums.txt`，校验行格式与路径集合，并逐成员比对 SHA-256 与大小 → `CHECKSUM_MISMATCH`。
7. 交叉校验 `manifest.members`（path/size/mode）与实际成员。
8. 全部通过后，把临时文件**原子改名**为 `<store-dir>/<sha256>.tar.gz`（同目录 `ATOMIC_MOVE`），再写入包索引记录。
   - 目标文件已存在（相同摘要）：直接视为已存在，返回成功且 `existing=true`，不覆盖。
   - 任一步失败：删除临时文件，返回失败；**绝不允许**部分上传成为包。

存储目录中任何形如 `.upload-*.part` 的文件都不是有效包：失败路径立即删除，应用启动时清理残留。

> 本节出现的 `MANIFEST_FORMAT_UNSUPPORTED`、`MANIFEST_INVALID`、`PACKAGE_PLATFORM_UNSUPPORTED`、`PACKAGE_NAME_MISMATCH`、
> `CHECKSUM_MISMATCH` 是**服务端内部校验原因**（用于日志、测试断言与实现内部分支）；
> **上传接口对外只返回固定中文 `msg`**，取值见 [frontend-api/node-package-api.md](frontend-api/node-package-api.md) §2，
> 不把这些原因码加进 `Result` 信封（决策 D12）。

## 8. 与三个仓库及下游阶段的边界

| 仓库 | 行 | 本契约赋予的职责 |
| --- | --- | --- |
| `D:\project\go\flowops-executor` | P2 | 产出符合 §1–§6 的单个 Linux amd64 包；提供可重复构建证据与无密钥证据；不改 `runner/**` 行为、不改协议 |
| `D:\project\backend\flowops` | P3 | 按 §7 校验、按 §1 摘要做不可变存储、经 SSH/SFTP 分发（路径与规则见[阶段 2 计划](2026-09-27-runner-package-distribution-plan.md)） |
| `D:\project\front\flowops-front` | P4 | 只消费主节点 API，不解析包内容 |
| `D:\project\mix\nexa-protocol` | — | 不修改 |
| 阶段 3 O1/O2/O3 | 下游 | 在目标机解包到 `/opt/flowops/runner/releases/<sha256>/`，解包后**必须**重新校验 `manifest.json` 与 `checksums.txt`；`docker load` 后按 `image.imageId` 核对装载结果；安装/启动时必须把 `manifest.package.version` 注入为 `FLOWOPS_RUNNER_VERSION`，使主节点注册信息里的 `runner.version` 等于包版本，便于“已安装版本 = 已上报版本”核对 |

### 版本一致性（P2 与阶段 3 共同遵守）

- `manifest.package.version` 是唯一权威版本号；
- P2 必须让二进制的内嵌 `runner.version`（非敏感字段，允许内嵌）等于该值；
- 阶段 3 安装/启动时必须注入 `FLOWOPS_RUNNER_VERSION=<manifest.package.version>`（ENV 优先级高于内嵌），
  使 `GET /api/nodes` 返回的 `version` 与包版本一致（该字段来自注册会话，见 `NodeInfoVO.version`）；
- 三者不一致时，以 `manifest.package.version` 为准，并作为缺陷回流到相应行。

## 9. 与执行器仓库既有实现的关系（冲突与处置）

执行器仓库已实施启动脚本计划（该仓库内 `docs/2026-09-24-unified-startup-script-plan.md`，状态为"已实施"），
其"部署包结构"为 `start.sh` + `scripts/start.sh` + `bin/<platform>/flowops-executor`（可选 `docker/start.sh`），
并允许"native/docker 按模式分别打包"。该结构与阶段 2/3 的 SSH 托管分发模型**不同**：

| 差异点 | 既有（2026-09-24 已实施） | 本契约（阶段 2/3） | 处置 |
| --- | --- | --- | --- |
| 二进制路径 | `bin/linux-amd64/flowops-executor` | `bin/flowops-executor` | 本契约固定；既有路径不得用于阶段 2 发布包 |
| 启动方式 | 自带 `start.sh`/`scripts/start.sh` 脚本 | 主节点渲染的 systemd unit / Compose 项目（阶段 3 O2/O3） | 启动脚本**不进入**阶段 2 发布包；保留在仓库中用于手工/自管部署 |
| 打包粒度 | 可按模式分别打包 | 单个包同时支持两种模式 | 本契约固定单包双模式（"两个运行模式使用同一个经过校验的版本"） |
| 目录/权限 | 由脚本自行 `chmod` | 主节点受控 SSH 操作，权限由管理账号预置 | 阶段 2 只投递文件，不 `chmod`、不执行 |

结论与要求：

- 阶段 2 的发布包格式**以本文件为准**；执行器仓库中已有的 `start.sh` / `scripts/` / `bin/<platform>/` 等文件**保留**，不删除、不改行为。
- **P2 不得**把启动脚本、`<platform>` 子目录或"按模式拆分"产物放进发布包。若认为必须包含启动脚本，先由协调者修订本文件并提升 `packageFormatVersion`，再改 P2。
- 冲突处置已记入[阶段 2 计划](2026-09-27-runner-package-distribution-plan.md)的 P1 定稿记录，供 P2 执行方核对。

## 10. 变更规则

- 新增/删除成员、改变路径或权限、改变 `manifest.json` 必填字段含义、改变归档确定性要求，都必须：**先修订本文件**，提升 `packageFormatVersion`（v1 → v2），并同步 P2/P3/O1 三行。
- 仅修改字段取值（如新 `<version>`、新镜像 digest、新工具链版本）不需要改格式版本。
- 主节点只接受与自身支持的格式版本一致的包；遇到更高版本按 `MANIFEST_FORMAT_UNSUPPORTED` 拒绝，不做"尽力解析"。

## 11. 2026-09-28 配置统一修订

本节按[配置统一与一键安装计划](2026-09-28-runner-configuration-and-one-click-install-plan.md)修订 §1、§6 中“任何环境专属取值”与“双环境内嵌配置”的表述：**允许构建时从选定 prod/dev 配置源编入非敏感运行默认值**（心跳、重连、日志等），仍禁止一切节点专属值与秘密。其余 v1 约束，包括六成员归档、manifest 字段、大小、摘要和可重复构建，保持不变；这次修订没有增加成员或修改 manifest 必填字段，因此 `packageFormatVersion` 仍为 `1`。修订尚未在 P2 代码中落地。

| 项 | 修订后规则 |
| --- | --- |
| 唯一配置源 | 执行器仓库受版本控制的 `config/profiles/prod.yaml` 与 `config/profiles/dev.yaml`；每个环境一份非敏感默认值，构建工具从所选文件生成内嵌配置，不再读取 `deploy/release-config/**` 重复源 |
| 环境选择 | `prod` 为打包默认值，`dev` 必须显式选择；只把所选环境的默认值编入本次二进制/镜像；运行时选择不同环境必须拒绝，不得静默切换到另一套默认值 |
| 版本区分 | dev 包的 `package.version` 必须带 `-dev` 预发布后缀，文件名、镜像引用、内嵌 `runner.version` 同步；prod 包不得带该后缀，避免同版本名称指向不同环境的包。包身份仍由 tar.gz SHA-256 决定 |
| 内嵌白名单 | 仅允许 `runner.heartbeat_interval`、`runner.connect_timeout`、`runner.reconnect_initial_interval`、`runner.reconnect_max_interval`、`runner.register_timeout`、`runner.auth_retry_interval`、`log.level`、`log.file_path`、`log.max_size`、`log.max_backups`、`log.max_age` 及构建生成的 `runner.version`；配置含其他字段时构建失败，不能静默丢弃 |
| 永不入包 | `runner.id`、`runner.master_addr`、`runner.token`、database 段、SSH 密钥、目标机配置路径以及任何实际节点凭据；即使这些值只会被编进二进制/镜像，也视为入包并拒绝 |
| 验收 | 仍执行 §6 的空配置拒绝启动与敏感值扫描；另测 prod/dev 选择、错误运行环境拒绝、双模式产物使用同一所选配置、dev/prod 版本区分和同输入可重复构建 |

节点专属 YAML 由**阶段 3**主节点安装作业生成，通过经主机密钥校验的 SSH/SFTP 写入目标机 `0600` 私有目录，不属于本归档，也不参与本包的摘要或缓存。native 通过 `--config`、container 通过只读挂载及 `FLOWOPS_CONFIG` 读取；版本继续由本 manifest 注入。阶段 2 上传与分发服务不得接受“把凭据附在包里”作为捷径。

## 12. 2026-09-28 P3 校验实现修订

本节记录主节点侧（阶段 2 P3）实现后对 §3、§5、§7 的细化。**未新增或删除归档成员，未改 `manifest.json` 必填字段集**，
因此 `packageFormatVersion` 仍为 `1`；本节取代上文冲突表述。

| 项 | 修订后规则 | 依据 |
| --- | --- | --- |
| 未知 `manifest.json` 字段 | **拒绝**（内部原因 `MANIFEST_INVALID`），取代 §3 的"忽略未知字段" | P3-C 要求包与目标机凭据分离；未知字段是夹带 `runner.id`/`token` 等值的通道。字段集变化按 §10 必须先提升格式版本，故拒绝不损害前向兼容 |
| 成员身份属性 | 主节点强制 `uid = gid = 0`、`uname`/`gname` 为空、`mode` 取 §2 白名单值、`mtime == package.sourceDateEpoch` | 与执行器仓库 `deploy/packager verify` 的判定对齐（§5.1 原只写成构建要求） |
| 只接受普通文件 | 判定依据是 tar 的 `linkFlag`（仅 `LF_NORMAL` / `LF_OLDNORM`）；目录、符号链接、硬链接、设备、FIFO 一律拒绝 | `TarArchiveEntry#isFile` 对链接也返回 `true`，不能作为判定依据 |
| 结构性文本成员上限 | `manifest.json`、`checksums.txt` 各 ≤ 256 KiB | 校验实现要求内存有界 |
| 上传校验流水线 | 两段式本地流水线：①把上传字节流式写入临时文件并同步计算包 SHA-256；②从该临时文件解析 gzip/tar 校验 | 单遍 tee 流水线会因解压器的 `mark/reset` 复读导致**重复落盘**（实测 1008 B 的上传写成 2006 B，摘要与内容不一致）。两段式下摘要严格等于落盘字节，内存有界、失败不留半成品 |
| 远端临时文件清理 | 失败清理用 SFTP `remove`，**不执行** `rm`；远端命令只用 `sha256sum --`、`df -Pk --`（空间预检，不可用时跳过）、`mv -f --` | 减少远端工具依赖；`REMOTE_TOOL_MISSING` 文案相应只列 `sha256sum / mv` |
| 分发并发 | 进程内有界线程池（默认 2 线程 / 队列 32）＋"同节点在途记录"串行判定；同摘要进行中返回既有记录 | 落实 API 契约 §4.2 的幂等与并发语义 |

实现位置与验证证据见[阶段 2 计划](2026-09-27-runner-package-distribution-plan.md)的
"2026-09-28 P3 与 P3-C 实施记录"（真实 Go 侧固件、进程内 SSH/SFTP 链路、77 项新测试）。

## 13. 2026-09-28 修订（D0 契约）：双端大小上限与阶段 3 模板兼容判定

> 本节是[阶段 2 发布包设计审阅](../issues/2026-09-28-phase-2-runner-package-design-review.md) **D0** 行的契约定稿，
> 落实 R3（构建端与主节点使用同一组大小上限，且两侧都流式处理）与 R4（阶段 3 安装前的模板兼容判定）。
> **未修改任何业务代码**；`packageFormatVersion` 仍为 `1`——本节不新增/删除归档成员、不新增 manifest 字段。
> 上文（含 §11、§12）保持原样，冲突处以本节为准。

### 13.1 唯一常量表（主节点与执行器工具必须一致）

| 项 | 上限 | 判定对象 | 超出时的行为 |
| --- | --- | --- | --- |
| 压缩包 `.tar.gz` | **1 GiB** = 1 073 741 824 字节 | 输出/输入文件字节数 | 构建端删除未完成输出并非零退出；主节点按 `PACKAGE_TOO_LARGE` 拒绝 |
| 单个成员（解压后） | **2 GiB** | 成员字节数 | 构建端失败；主节点 `MEMBER_TOO_LARGE` |
| 全部成员解压总计 | **4 GiB** | 成员字节数之和 | 同上 |
| 归档成员数 | **64**（v1 实际为 6） | tar 条目数 | 构建端失败；主节点 `MEMBER_INVALID` |

两侧都必须**流式**处理：

- 执行器 **构建**：打包前流式核对载荷大小；写 tar/gzip 全程流式，**不得**整份读入镜像 tar 或任何成员
  （当前 `os.ReadFile`/`io.ReadAll` 的做法即违反本要求）；写出后检查 `.tar.gz` 字节数。
- 执行器 **verify**：逐成员流式计算摘要与大小（不得 `io.ReadAll` 整个成员），执行同一组上限；
  只在内存中保留有界的 `manifest.json` / `checksums.txt`。
- 主节点（P3 已实现）：同一组上限，另对 `manifest.json` / `checksums.txt` 各限 256 KiB（见 §12）。

**构建端失败语义**（`packager package` 与 `deploy/release.sh|ps1` 一致）：

```text
[ERROR] 发布包超出契约上限: <项>（<实际> > <上限>）
```

- 非零退出；**删除未完成的输出文件**，不得留下半成品让编排脚本误判成功（当前 `release.sh` 只清理扫描临时文件）；
- 同一次构建失败不得影响 `dist/` 中已存在的其他产物。

**双端一致验收**：同一合法包必须同时通过 `packager verify` 与主节点校验器且摘要一致；构造超限样本时两端都以各自固定错误拒绝，
且构建端无残留输出。

### 13.2 阶段 3 模板兼容判定（R4）

包不可变，旧包会长期存在，因此阶段 3 必须在**安装任何候选包之前**机械判定模板是否符合"凭据只从节点专属 `0600` YAML 读取"。

**占位符词汇**（新增 `{{PRIVATE_CONFIG}}`；不使用其它占位符，新增需先修订本节）

| 占位符 | 渲染值 |
| --- | --- |
| `{{RELEASE_DIR}}` | `/opt/flowops/runner/releases/<package-sha256>` |
| `{{IMAGE_REFERENCE}}` | `manifest.image.reference` |
| `{{PRIVATE_CONFIG}}` | `/opt/flowops/runner/private/<nodeKey>.yaml`，`<nodeKey>` = UTF-8 `runnerId` 的 SHA-256 小写十六进制 |

**判定标准**（对包内 `templates/*` 文本做机械检查，大小写不敏感；容器内固定路径为 `/etc/flowops/executor.yaml`）

| 模板成员 | 必须存在 | 必须不存在 |
| --- | --- | --- |
| `templates/flowops-executor.service` | `ExecStart=` 指向 `{{RELEASE_DIR}}/bin/flowops-executor` 且带 `--config {{PRIVATE_CONFIG}}` | `EnvironmentFile`；把 `FLOWOPS_RUNNER_ID`／`FLOWOPS_MASTER_ADDR`／`FLOWOPS_RUNNER_TOKEN` 作为环境注入；任何 token 赋值 |
| `templates/docker-compose.yaml` | 只读挂载 `{{PRIVATE_CONFIG}}:/etc/flowops/executor.yaml:ro`，且 `FLOWOPS_CONFIG` 指向 `/etc/flowops/executor.yaml` | `env_file`；`FLOWOPS_RUNNER_TOKEN`；把凭据写进 `environment:`（允许保留 `APP_ENV`、`FLOWOPS_RUNNER_VERSION`） |

| 项 | 定稿规则 |
| --- | --- |
| 判定时机 | 阶段 3 安装作业内、**生成或轮换 token 之前**；不通过则作业立即失败，登记表 token 哈希与目标机均不改动 |
| 拒绝响应 | 固定错误码 `TEMPLATE_INCOMPATIBLE`，文案「该发布包的启动模板不符合节点凭据读取约定，不能安装」，作业记录保留阶段与包摘要 |
| 判定对象 | 所有候选包，含阶段 2 已入库的旧包（旧 `executor.env` 形态 → 判不兼容） |
| 读取方式 | 主节点从**已存储的 `.tar.gz`** 流式读取 `templates/*` 文本：不解压落盘、不执行包内内容 |
| 与阶段 2 的边界 | 阶段 2 分发 API 不实现该门禁：旧包仍可分发且分发记录有效，但**分发成功 ≠ 可安装**；阶段 2 不写节点凭据、不安装、不启动、不轮换 token |
| 格式影响 | 模板内容变化不改变归档成员集合与 manifest 字段集，因此 `packageFormatVersion` 仍为 `1`；若将来需要新增成员或字段，先按 §10 修订并升级格式版本 |

### 13.3 与 §12 的关系

§12 是主节点（P3）**校验实现**的修订；§13 是 D0 的**跨仓契约**修订（尺寸上限在双端生效 + 阶段 3 模板门禁）。
两者都不改变 v1 的成员集合与 manifest 字段集，因此既有固件、已入库包与 P3-C 结论继续有效。
