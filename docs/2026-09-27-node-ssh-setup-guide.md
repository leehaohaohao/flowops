# 节点管理 SSH 设置操作指南

本文配套[阶段 1 SSH 连接验证计划](2026-09-27-node-ssh-connection-plan.md)。当前测试目标是 **Linux amd64 子节点宿主机**，不是 runner 容器。Windows 可以作为管理员操作电脑；Windows **作为 SSH 目标**尚未纳入本阶段验收。测试只验证 SSH 握手、主机身份、公钥认证、`true` 命令及 SFTP，不安装 runner，也不验证 Docker。

## 表单各项数据如何获得

| 字段 | 获取与填写方法 |
| --- | --- |
| 节点 ID（`runnerId`） | 先在“节点管理 → 节点登记”创建的节点 ID。它是 FlowOps 节点身份，不是 SSH 用户名；节点注册令牌也不填入 SSH 表单。 |
| 宿主机地址（`host`） | 子节点 **Linux 宿主机**可从主节点容器访问的 IP 或 DNS 名称。目标机执行 `hostname -I` 可查看地址；有多个地址时选主节点网络实际可达的一个。只填地址，不带 `ssh://`、端口或路径；不要填 runner 容器内部 IP。 |
| SSH 端口（`port`） | 目标机 SSH 服务的实际监听端口，通常为 `22`。目标 Linux 执行 `sudo sshd -T | grep '^port '` 核对有效配置，或执行 `sudo ss -ltnp | grep sshd` 核对监听。 |
| SSH 用户名（`username`） | 安装了公钥的目标 Linux 用户，在其登录会话执行 `whoami`。当前只接受字母、数字、点、下划线、连字符，不接受域名前缀或 `user@domain` 形式。此阶段不需要授予 Docker 权限。 |
| 私钥别名（`keyAlias`） | 管理员给**主节点私钥文件**选的文件名，例如 `runner-1`；后端读取容器内 `/data/flowops/ssh-keys/runner-1`。只填 `runner-1`，不填路径、`.pub` 或私钥内容。别名仅允许字母、数字、点、下划线、连字符，不能是 `.` 或 `..`。 |
| 主机密钥指纹（`hostKeySha256`） | **目标 SSH 服务器的主机公钥**的 SHA-256 指纹，不是登录密钥 `.pub` 的指纹。目标机可信控制台执行 `sudo ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ed25519_key.pub`，从输出复制 `SHA256:...`。若实际协商其他主机密钥算法，须核对相应 `/etc/ssh/ssh_host_*.pub`。不要仅凭网络扫描值自动信任。 |

默认密钥目录为主节点容器内 `/data/flowops/ssh-keys`。`deploy-prod.sh` 把宿主机 `/data/flowops` 挂到容器同一路径；若使用其他部署方式，先核对挂载与 `flowops.ssh.key-dir`。页面 `keyFileExists` 只说明后端看到私钥文件，不代表连接成功。

## Linux 目标机：准备 SSH 与登录密钥

命令中的 `runner-1`、`<目标用户名>`、`<目标地址>` 按实际环境替换。私钥始终留在主节点，不要粘贴到网页、工单或日志。

1. 在**目标 Linux 宿主机**确认 SSH 服务已启动。Ubuntu/Debian 缺少服务时由管理员执行：

   ```bash
   sudo apt update
   sudo apt install openssh-server
   sudo systemctl enable --now ssh
   sudo systemctl status ssh
   sudo sshd -T | grep '^port '
   ```

   其他发行版使用自身包管理器和 `sshd` 服务名。按目标机防火墙、云安全组规则，对主节点来源开放实际 SSH TCP 端口。

2. 在**运行 FlowOps 容器的主节点 Linux 宿主机**预置专用密钥。当前后端不支持带口令私钥；以下生成无口令密钥，需要依靠主机访问控制及文件权限保护。确认文件不存在后再生成，避免覆盖已有密钥。

   ```bash
   sudo install -d -m 700 -o root -g root /data/flowops/ssh-keys
   sudo ssh-keygen -t ed25519 -N '' -f /data/flowops/ssh-keys/runner-1
   sudo chmod 600 /data/flowops/ssh-keys/runner-1
   sudo chmod 644 /data/flowops/ssh-keys/runner-1.pub
   ```

   也可以把已有密钥对中的无口令私钥安全预置到该目录，文件名即别名；不要用符号链接。若系统以非 root 身份运行容器，按实际运行用户调整属主与读取权限。

3. 把 **`runner-1.pub` 公钥**放入目标 Linux 用户的 `~/.ssh/authorized_keys`。若允许先用已有凭据登录，在主节点执行：

   ```bash
   sudo ssh-copy-id -i /data/flowops/ssh-keys/runner-1.pub -p 22 <目标用户名>@<目标地址>
   ```

   首次执行时，先用步骤 4 从目标机可信控制台核对 SSH 提示的主机指纹，再接受连接。若无 `ssh-copy-id`，在目标机以该用户登录，执行 `install -d -m 700 ~/.ssh`，把主节点 `.pub` 文件的完整单行内容追加到 `~/.ssh/authorized_keys`，再执行 `chmod 600 ~/.ssh/authorized_keys`。只传递公钥，私钥不离开主节点。非 22 端口须替换命令中的端口。

4. 在**目标机可信控制台**获取主机密钥指纹：

   ```bash
   sudo ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ed25519_key.pub
   ```

   只把 `SHA256:...` 填入表单。目标可能启用多个主机密钥；如返回 `HOST_KEY_MISMATCH`，应在目标控制台核对启用的主机公钥及协商算法，不能为了通过测试而填写未经核实的扫描值或关闭校验。当前实测发现一例“填写的 ED25519 指纹与握手观察指纹不同”，排查与修复顺序见[SSH 计划 H1](2026-09-27-node-ssh-connection-plan.md#真实联调问题-h1主机密钥指纹不一致待排查)。重装/更换主机密钥后需重新核对并更新。

## Windows 和 Linux 管理电脑如何操作

管理电脑的系统不改变目标要求：当前目标仍为 Linux。Windows 管理员在 **PowerShell**、Linux 管理员在终端可分别执行：

| 操作 | Windows PowerShell | Linux 终端 |
| --- | --- | --- |
| 检查管理电脑到目标机端口 | `Test-NetConnection <目标地址> -Port 22` | `nc -vz <目标地址> 22`（已安装 netcat 时） |
| 登录目标 Linux 宿主机 | `ssh -p 22 <目标用户名>@<目标地址>` | `ssh -p 22 <目标用户名>@<目标地址>` |
| 登录后取得用户名、端口、指纹 | 在 SSH 会话执行上文 Linux 的 `whoami`、`sudo sshd -T`、`sudo ssh-keygen` 命令 | 同左 |

管理电脑可达不代表**FlowOps 主节点容器**可达，最终以页面“测试连接”为准。Windows 管理电脑不必保存或上传 FlowOps 的私钥。若主节点本身运行在 Windows 上，以上 `/data/flowops` 挂载与 POSIX 权限步骤不适用，需为该部署方式单独设计密钥目录与权限。

## 页面填写与验收步骤

1. 确认目标数据库已执行 `V3_1_4__create_nexa_node_ssh_target.sql` 与 `V3_1_5__add_ssh_target_test_guard.sql`；仅有迁移文件不等于数据库已更新。
2. 使用**超级管理员**进入“节点管理 → 节点登记”，先创建目标 runner ID。登记令牌单独保管，与 SSH 私钥无关。
3. 在该节点行打开“SSH 设置”，填入上述五个字段并保存。若页面提示私钥不存在，核对主节点**容器内**的密钥路径、别名和挂载。
4. 点击“测试连接”。`CONNECTED` 表示握手、主机指纹、认证、远端 `true` 命令和 SFTP 通道全部通过。记录 runner ID、目标地址、指纹的可信核验方式、测试时间与结果，供阶段 S4 验收。
5. 分别用错误指纹、错误密钥核对失败路径，并检查非超级管理员不能保存或测试。恢复正确设置后重新测试；修改设置会清除旧结果。

| 结果 | 优先排查 |
| --- | --- |
| `KEY_NOT_FOUND` / `KEY_UNREADABLE` | 主节点**容器内**的 `/data/flowops/ssh-keys/<keyAlias>` 是否为可读、无口令的普通私钥；别名是否误填 `.pub` 或路径。 |
| `KEY_PERMISSION_TOO_OPEN` | 主节点私钥权限是否为 `0600` 或更严。 |
| `CONNECT_FAILED` / `CONNECT_TIMEOUT` | 主节点容器到目标宿主机的地址、端口、路由、防火墙和 SSH 服务。 |
| `HOST_KEY_MISMATCH` | 从目标可信控制台重新核对**服务器主机公钥**指纹及启用算法。 |
| `AUTH_FAILED` | 用户名、该用户的 `authorized_keys` 内容与权限、公钥认证设置。 |
| `COMMAND_FAILED` / `SFTP_FAILED` | 目标用户 shell 能否执行 `true`，SSH 服务是否启用 SFTP；受限 shell 或强制命令也可能阻断。 |
| `TEST_OBSOLETE` | 测试期间设置被修改或同节点已有更新的测试；刷新当前节点设置并重新测试。 |

## Windows 作为目标机：字段获取与现阶段限制

若要**记录** Windows 目标机信息，可在其管理员 PowerShell 中执行以下命令。它们并不表示当前 FlowOps 支持把 Windows 目标验收为已连接。如尚未安装 OpenSSH Server，先在管理员 PowerShell 执行安装和启动步骤：

```powershell
Get-WindowsCapability -Online | Where-Object Name -like 'OpenSSH.Server*'
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Start-Service sshd
Set-Service -Name sshd -StartupType Automatic
Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
```

最后一条若没有返回允许 TCP 22 入站的规则，按[微软安装文档](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse)创建相应防火墙规则；如果 SSH 服务使用其他端口，也要同步调整监听与规则。然后取得候选字段：

```powershell
Get-Service sshd
Get-NetTCPConnection -State Listen -LocalPort 22
$env:USERNAME
Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -NotLike '127.*'
ssh-keygen -E sha256 -lf "$env:ProgramData\ssh\ssh_host_ed25519_key.pub"
```

从主节点取出 `.pub` 公钥，按[微软密钥管理文档](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_keymanagement)放入相应账户的授权文件并设置权限：普通用户为 `%USERPROFILE%\.ssh\authorized_keys`；管理员账户默认使用 `C:\ProgramData\ssh\administrators_authorized_keys`，须设置专门 ACL。域账户名通常不符合当前表单校验。主机指纹仍须在 Windows 目标机可信控制台核对，不能从未认证的扫描输出直接采信。

**当前限制：** FlowOps 固定发送 Unix 命令 `true`；Windows OpenSSH Server 默认 shell 为 `cmd.exe`，没有该命令。因此即使网络、指纹和认证通过，通常仍返回 `COMMAND_FAILED`。Windows 目标的命令探测、SFTP/权限语义和后续运行方式需另立跨平台实现与验收计划；不要把字段采集当成支持 Windows 子节点部署。

参考：[Ubuntu OpenSSH Server 指南](https://ubuntu.com/server/docs/how-to/security/openssh-server/)、[OpenBSD ssh-keygen 手册](https://man.openbsd.org/ssh-keygen)、[微软 OpenSSH Server 配置（默认 shell）](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh-server-configuration)。
