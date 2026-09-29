# 待修复：子节点发布包目录创建与分发结果展示

> 状态：待实施。2026-09-29 的真实联调中，向 `runner-dev-2` 分发已上传的包时返回 `REMOTE_DIR_MISSING`；管理员手工创建目录后尚未以本方案重新验收。同次联调还发现分发记录的“结果”列被挤成细长竖条。本文是后续修改计划，不代表当前代码或页面已修复。

## 问题与确定的处理方式

当前 `RunnerPackageDistributor.requireRemoteDir()` 仅检查目标机的 `flowops.runner-package.remote-dir`（默认 `/opt/flowops/runner/packages`）。目录不存在就返回 `REMOTE_DIR_MISSING`，分发结束时还会尝试清理并不存在的 `.part` 文件，产生一条次生 `SftpException` 告警。原[阶段二分发计划](../2026-09-27-runner-package-distribution-plan.md)的 D10 明确要求运维预置目录；本问题记录后续要变更该约定，当前实现仍以原约定为准。

修复后，分发流程在认证成功、建立 SFTP 会话后，先检查固定配置路径；缺失的目录由**已配置的 SSH 用户**通过 SFTP 逐级创建，随后继续现有摘要校验、上传和原子改名。已存在的目录保持原权限和内容，不对目标机执行 `sudo`、`chmod` 或 `chown`。目标路径仅来自后端配置，不能由上传请求、包内容或节点表单指定；拒绝非绝对路径、`.`/`..` 路径段、符号链接及被普通文件占用的路径段。

自动创建不等于自动提权。全新 Linux 主机的 `/opt` 通常归 root 且不可由普通 SSH 用户写入；此时程序必须返回 `REMOTE_DIR_NOT_WRITABLE`，页面明确提示管理员预置可写的父目录或调整目标目录配置，然后重试。不能把权限错误继续报成 `REMOTE_DIR_MISSING`，也不能为了“成功”而启用远端 sudo。`REMOTE_DIR_MISSING` 仅在创建成功后的复查中仍确认目录不存在时使用；路径被文件/链接占用使用新增 `REMOTE_DIR_INVALID`。连接断开继续按现有连接/传输错误处理。

此目录是**目标机保存已验证发布包**的位置：包以 SHA-256 命名，供后续安装阶段取用。本次变更只处理分发前的目录准备，不解包、不启动子节点，也不改变包格式、包路径和凭据传递机制。

## 前端分发记录“结果”列布局问题

`NodeList.tsx` 的分发记录抽屉宽度为 720px。表格其余七列已指定的宽度合计 830px，“结果”列却没有宽度，也没有横向滚动设置；失败原因和成功目标路径都是完整长文本。用户看到的结果是该列被压到一行只有几个字符、整行向下拉得很长。此判断来自当前列定义与用户反馈，尚未做不同屏宽的视觉验收。

修复时给“结果”列 280px 宽度，表格设置 `scroll.x=1200`；长文案在单元格中最多显示两行并以省略号截断，悬停或聚焦时能查看完整内容。成功路径、失败原因、进行中三种状态都走同一布局规则；完整错误文案不得因视觉截断而从可访问文本中消失。抽屉仍保持 720px，不靠无限加宽抽屉掩盖列宽问题。表格在窄屏允许横向滚动，不让“结果”列缩成逐字换行的竖条。

## 跨仓库契约与归属

| 契约/仓库 | 绝对路径 | 确定责任 |
| --- | --- | --- |
| 分发错误码与 API 契约 | `/home/lihao/projects/flowops` | 后端先定稿 `REMOTE_DIR_NOT_WRITABLE`、`REMOTE_DIR_INVALID` 和保留的 `REMOTE_DIR_MISSING` 的触发条件与中文文案；同步更新 `docs/frontend-api/node-package-api.md`，再允许前后端实现。 |
| 后端实现与测试 | `/home/lihao/projects/flowops` | 只修改目标目录预检/创建、错误映射与对应测试；在原分发计划末尾追加新决策，不改写原始 D10 记录。 |
| 前端展示 | `/home/lihao/projects/flowops-front` | F1 在后端契约定稿后更新错误码文案；F2 单独修复分发记录表格布局与完整文案查看，不修改分发协议。 |

## 开发顺序表

| 行 | 仓库 | 具体任务 | 前置行 | 允许修改的文件/模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C0 | 后端 `/home/lihao/projects/flowops` | 定稿缺失、权限拒绝、路径非法、连接中断的错误码和中文提示；将“SSH 用户可创建则自动创建，否则提示预置权限”的新约定追加到阶段二分发计划。 | 无 | `docs/frontend-api/node-package-api.md`、`docs/2026-09-27-runner-package-distribution-plan.md`、本 issue | 前后端共同使用的唯一契约；由后端协调者确认后冻结 | 对照 `PackageDistributionErrorCode` 与当前前端映射，逐项列出旧码兼容关系 | 不实施代码；不改包格式或 SSH 身份模型 |
| B1 | 后端 `/home/lihao/projects/flowops` | 把 `requireRemoteDir()` 改为受限的 SFTP 逐级检查/创建；并发创建后复查目录；创建失败按 C0 映射；目录预检失败时不进入 `.part` 清理。 | C0 | `flowops-app/src/main/java/com/nexa/flowops/service/nodepackage/RunnerPackageDistributor.java`、`RunnerPackageSettings.java`、`PackageDistributionErrorCode.java` | 可创建的目录自动就绪，权限/非法路径有准确失败码 | 有写权限的空父目录可分发；无写权限 `/opt` 返回权限码；已有目录与同摘要重试保持原行为 | 不执行 `sudo`/`chmod`/`chown`，不允许客户端传目标路径，不安装或启动 runner |
| B2 | 后端 `/home/lihao/projects/flowops` | 补齐目录创建和错误分类的确定性测试，并检查现有分发状态机/清理逻辑。 | B1 | `flowops-app/src/test/java/com/nexa/flowops/service/nodepackage/RunnerPackageDistributor*Test.java` 及必要的同模块测试夹具 | 缺失可创建、并发已创建、权限拒绝、文件/链接占位、连接中断、预检失败不产生新 `.part` 的测试证据 | 运行相关 Maven 测试；确认 `SUCCEEDED`/`FAILED` 记录与错误码一致 | 不把模拟 SFTP 测试当成真实 SSH 验收 |
| F1 | 前端 `/home/lihao/projects/flowops-front` | 按 C0 更新分发结果文案，使权限不足明确提示预置父目录或调整目标目录；展示路径非法码。 | C0 | `src/pages/NodeList.tsx`、`src/pages/NodeList.test.tsx`、必要的 `src/types/index.ts` | 错误结果可读且与后端码一致 | `npm test` 中的节点分发用例及 `npm run build` | 不改节点 SSH 设置流程或其他页面 |
| F2 | 前端 `/home/lihao/projects/flowops-front` | 给分发记录“结果”列 280px 宽度与两行截断/完整内容查看能力；设置 `scroll.x=1200`，覆盖失败原因、成功路径和进行中状态。 | F1（两行同改 `NodeList.tsx`，按顺序实施，避免并发覆盖） | `src/pages/NodeList.tsx`、`src/pages/NodeList.test.tsx`；如需局部样式，仅限该页面样式文件 | 720px 抽屉内结果不再逐字换行，完整文案仍可查看 | `npm run build`；节点分发测试核对完整文案可访问；人工检查桌面与窄屏的长中文错误、长路径及横向滚动 | 不改全局表格样式，不改其他列业务含义或后端返回值 |
| V1 | 后端 `/home/lihao/projects/flowops` | 在真实 Ubuntu/Debian SSH 目标机完成两组联调：SSH 用户可写父目录时自动创建并分发；`/opt` 不可写时明确返回权限码。记录结果后关闭本 issue。 | B2、F2 | 本 issue 的验收记录；仅在发现代码缺陷时回到对应实施行重新分派 | 真实环境证据及完成状态 | 核对远端仅出现固定目录与摘要命名包；失败不产生正式包或异常清理告警；前端错误文案与 API 一致，长结果列在桌面和窄屏均可读 | 不在验收行顺手实现后续安装阶段 |

每行只由其所属仓库的执行者修改列出的范围；需要跨界改动时先交回协调者调整表格。协调者在 V1 对照代码、测试和真实环境记录做最终集成审阅。

## 现实边界与验收前置

- 当前 `/opt/flowops/runner/packages` 已在本地 WSL 目标机由管理员手工建好，**不能**用它单独证明“缺失时自动创建”。验收必须另备可删除的测试目录或隔离目标机，且不得删除现有正式包。
- 对全新主机，若坚持默认 `/opt/flowops/runner/packages`，管理员仍须先给 SSH 用户一个可写父目录；程序只能创建权限允许的余下目录。权限不足时提示这一动作就是预期结果。
- 当前日志中的 `SftpException` 是缺目录后的清理尝试产生的次生告警；B1 应阻止预检失败进入清理逻辑，保留真正传输后清理失败的告警。
