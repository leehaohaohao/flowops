# FlowOps 文档索引

本文档索引覆盖 `docs/` 的规范、计划、操作指南、待修复问题、复盘与历史资料。**计划日期和标题不代表实现状态**；开始开发或宣称完成前，核对当前代码、测试、数据库迁移与真实环境验收记录。

文档按主题导航；已归档计划移入 [archive/](archive/README.md)，相关引用同步更新。归档是资料整理，不代表计划已完成。新发现且未实施的问题统一登记在 [issues/](issues/)。

## 当前分布式开发主线（按依赖顺序）

| 阶段 | 文档 | 用途 |
| --- | --- | --- |
| 阶段 1 | [SSH 连接验证计划](2026-09-27-node-ssh-connection-plan.md) | 主节点到子节点宿主机的 SSH 验证；H1/S4 以真实验收为准 |
| 阶段 1 | [SSH 设置操作指南](2026-09-27-node-ssh-setup-guide.md) | 表单数据来源、Windows/Linux 管理操作与平台边界 |
| 阶段 2 | [发布包格式契约](runner-package-format.md) | 六成员通用包、manifest、校验、无节点凭据约束；跨仓权威契约 |
| 阶段 2 | [发布包分发计划](2026-09-27-runner-package-distribution-plan.md) | P1—P5：构建、上传、SSH/SFTP 分发与验收；原始内容及追加修订 |
| 阶段 2/3 修订 | [配置统一与一键安装计划](2026-09-28-runner-configuration-and-one-click-install-plan.md) | 按环境构建非敏感默认值、首次安装生成 token、节点专属 YAML |
| 阶段 3 | [安装与节点管理操作计划](2026-09-27-runner-start-and-node-operations-plan.md) | native/container 安装、启动、停止、凭据与上线验收 |
| 后续宿主机执行 | [Docker 执行与 SSH 安装计划](2026-09-27-runner-host-docker-execution-and-ssh-install-plan.md) | 子节点容器外 Docker 执行与 SSH 托管安装的设计背景；与阶段 2/3 契约交叉核对 |
| 后续部署闭环 | [宿主机 Docker 部署计划](2026-09-27-runner-host-docker-deployment-plan.md) | 远端业务部署完整闭环，前置阶段未验收前不按已完成处理 |

## 运行规范与接口

| 文档 | 用途 |
| --- | --- |
| [配置加载顺序](configuration-loading-order.md) | 应用配置来源、优先级、容器环境与回归验证 |
| [前端 API 索引](frontend-api/README.md) | 节点、发布包及服务部署接口契约；该目录有自己的完整目录 |

## 待修复、复盘与历史资料

| 目录 | 内容 |
| --- | --- |
| [待修复问题](issues/README.md) | 需排在既定阶段之后处理的问题、前置与开发顺序表；当前含节点管理授权统一 |
| [复盘](mr/README.md) | 已发生问题的事实、处理思路和后续约束 |
| [历史归档](archive/README.md) | 早期计划、架构和前端对接历史；不据日期推断实现状态 |
| [AI 辅助开发资料](superpowers/README.md) | 早期 specs、plans 与 review/question 文件 |

新增文档时先选择上述主题或在 `issues/` 登记待修复事项；如需迁移已有文件路径，必须同步更新仓库内外引用并核对链接。
