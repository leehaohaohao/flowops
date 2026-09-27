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
