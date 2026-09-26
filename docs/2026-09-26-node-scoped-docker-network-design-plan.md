# 主节点 Docker 网络管理与项目接入：分项目开发计划

> 状态（2026-09-26 更新）：后端 B1–B4 已实现，`flowops-app` 全量单元测试通过；B2 的真实 Docker 操作与 B3 的真实 HTTP/DB 集成尚未验证，F0、F1–F3 未开始，I0 未执行。表中其余验收项均不是当前完成状态。  
> 日期：2026-09-26。统筹方负责分配任务、确认接口契约、集成审阅和更新本文档。  
> 本期交付：修复 Go 服务配置保存缺陷；只在主节点创建/导入和使用 Docker 用户自定义 `bridge` 网络。  
> 本期不交付：子节点 Docker 网络操作、Nexa Protocol 扩展、跨节点通信方案。

## 一、仓库与不可越界范围

| 项目 | 绝对路径 | 本期职责 | 本期边界 |
| --- | --- | --- | --- |
| 前端 FlowOps | `D:\project\front\flowops-front` | Go 配置缺陷修复；网络管理、项目授权/默认值和服务选择界面 | 只改前端仓库；不新增后端接口的实际实现，不改 runner 或协议 |
| 后端 FlowOps | `D:\project\backend\flowops` | 数据迁移、权限与 API、主节点 Docker 网络操作、Compose 生成和部署校验 | 只改后端仓库；不得实现子节点网络操作或更改跨节点协议 |
| Go runner | `D:\project\go\flowops-executor` | 无本期任务 | 不修改 |
| Nexa Protocol | `D:\project\mix\nexa-protocol` | 无本期任务 | 不修改 |

执行者只领取下表中的一行；该行的“允许范围”是写入边界，其他行和其他仓库均不可顺手修改。若发现必须跨边界改动，先向统筹方报告，由统筹方调整计划和分配。计划状态与验收证据仅由统筹方汇总；独立执行者不得自行宣布整份计划完成。

## 二、开发顺序表

| 顺序 / 任务 | 项目与允许范围 | 具体工作及交付物 | 前置 | 验收证据 | 明确不做 |
| --- | --- | --- | --- | --- | --- |
| F0：修复 Go 配置保存 | 前端；`src/pages/ServiceEdit.tsx`、对应测试，以及仅为运行这些测试所需的 `package.json`、`package-lock.json` 和测试配置 | 从完整表单存储序列化配置；运行时、上传控件、默认镜像/命令使用同一表单值；Go 空值按 Go 预设补齐；保留自定义值；对已有 Go+Java 默认组合显示提示及“恢复 Go 默认”操作；建立前端测试命令 | 无 | 未展开/已展开高级选项、上传二进制后保存、只改其他字段、Go/Java 切换、自定义值回归测试；重开表单与保存请求一致 | 不改上传接口、后端配置、网络功能、其他页面 |
| B1：网络数据模型 | 后端；`flowops-app/src/main/resources/sql/**`、`flowops-app/src/main/java/com/nexa/flowops/entity/DeployService.java`、新增网络实体/Mapper 及其测试 | 网络登记表、项目授权表、项目默认值表、`deploy_service.network_id` 迁移及初始化 SQL；旧服务为 `NULL`；唯一性和引用约束 | 无；接口字段遵守第三节 | 迁移在空库与已有数据场景验证；旧服务查询和部署不受影响 | 不实现 API、Docker 命令、页面、runner |
| B2：主节点 Docker 网络操作 | 后端；`flowops-docker/src/main/java/**/docker/**`、新增 `flowops-app/src/main/java/**/service/network/**` 及对应测试 | 固定参数执行 `docker network inspect/create/rm`；只创建默认地址分配的用户自定义 `bridge`；导入时识别已有网络；区分 managed/imported；状态对账和失败补偿 | B1 | 本机 Docker 实测创建、导入、缺失、重名、使用中拒删；导入网络取消登记不删除 Docker 实体 | 不调用 runner，不改 Nexa Protocol，不接受任意 Shell 命令 |
| B3：网络 API 与授权 | 后端；新增网络 Controller/DTO、`flowops-app/src/main/java/**/service/network/**`、对应测试；项目权限查询仅调用现有 `flowops-permission` 服务 | 实现第三节固定接口；全局管理仅超级管理员；项目主管只改已授权网络的默认值；`EDIT_CONFIG` 用户只读本项目可选网络；删除和撤权检查引用 | B1、B2 | 角色矩阵接口测试、直接请求越权测试、引用拒删/撤权、Docker 状态不一致响应 | 不改前端或 `flowops-permission` 模块，不给项目角色全局网络管理权限 |
| B4：服务接入与 Compose | 后端；`CreateServiceRequest.java`、`UpdateServiceRequest.java`、`ServiceMgmtService.java`、`DeployContext.java`、`ComposeYmlGenerator.java`、`DeployExecutorService.java`、`LocalDeployRunner.java` 及对应测试 | 服务保存 `networkId`；创建/更新/部署前校验主节点、项目授权和网络存在；生成 `default` + 外部 `shared` 双网络与唯一别名；旧服务生成结果不变 | B1、B3 | Go/Java 无关的 Compose 配置测试；全栈内部 `backend` 可达；runner/`auto` 与网络组合被拒绝 | 不实现子节点网络、不改变无网络服务的调度行为 |
| F1：网络管理页 | 前端；`src/pages/NetworkList.tsx`、`src/api/networks.ts`、新增网络抽屉组件、`src/types/index.ts`、`src/App.tsx`、`src/layouts/MainLayout.tsx` 及对应测试 | 超级管理员专属 `/networks` 页面；主节点网络列表、创建、导入、授权、删除/取消登记和状态展示；导出复用的创建/导入抽屉 | B3 接口契约冻结；可用模拟响应先开发 | 页面和权限可见性测试；构建通过；管理错误信息可见 | 不改服务编辑页、项目表单、后端 |
| F2：项目网络入口 | 前端；`src/pages/ProjectList.tsx`、`src/api/projects.ts`、`src/types/index.ts` 及对应测试；只调用 F1 暴露的抽屉 | 项目创建后授权主节点网络并设默认值；项目主管编辑时只在已授权网络中调整默认值；超级管理员可从项目入口打开新建/导入抽屉 | B3、F1 | 项目主管与超级管理员操作路径、失败步骤提示、普通角色无管理入口 | 不另写网络管理抽屉，不改服务页或后端 |
| F3：服务网络选择 | 前端；`src/pages/ServiceEdit.tsx`、`src/api/services.ts`、`src/types/index.ts` 及对应测试；复用 F1 抽屉 | “目标节点”旁增加一个共享网络选择；本机仅列本项目授权网络；新建预选项目默认值，编辑回填保存值；切到 runner/`auto` 清空网络；超级管理员可新建/导入并授权后再选 | F0、B4、F1 | 网络选择、节点联动、保存回填、重新部署提示；F0 回归测试继续通过 | 不改网络管理页、项目表单、后端 |
| I0：统筹集成验收 | 统筹方；只更新本计划的实施状态与验收记录，代码问题退回对应任务 | 在主节点执行迁移、后端测试、前端构建和真实 Docker 验证；逐行核对交付和权限；记录缺陷与归属 | F0、B1–B4、F1–F3 | 第六节全部验收项和执行证据 | 不把子节点或跨节点通信写成已完成；不替执行者越界修改其他仓库 |

B1 与 F0 可独立并行；同一仓库内有文件重叠的行按前置顺序交接。F1 可以在 B3 契约冻结后使用模拟响应开发，但只有 B3 完成后才能做真实接口验收。

## 三、跨项目接口契约

本节由统筹方冻结后交给 B3、B4 和 F1–F3；执行者不能单方更名字段或路径。所有 API 均沿用现有登录鉴权和 `Result` 响应格式，服务端权限检查不能由前端按钮显隐替代。

| 接口 / 字段 | 请求与响应要点 | 权限与规则 |
| --- | --- | --- |
| `GET /api/networks` | 返回主节点已登记网络、来源、Docker 状态、授权项目数、引用服务数 | 仅超级管理员 |
| `GET /api/networks/importable` | 返回主节点未登记的用户自定义 `bridge` 网络 | 仅超级管理员；排除 Docker 内置 `bridge` |
| `POST /api/networks` | `{name, displayName}`；创建并登记 managed 网络 | 仅超级管理员；只支持 `bridge`，Docker 默认地址分配 |
| `POST /api/networks/import` | `{name, displayName}`；登记已存在网络为 imported | 仅超级管理员；先 Docker inspect |
| `DELETE /api/networks/{networkId}` | managed 删除 Docker 实体及登记；imported 仅取消登记 | 仅超级管理员；两类网络有授权、默认值或服务引用时均拒绝；managed 仍有 Docker 容器连接时也拒绝 |
| `PUT/DELETE /api/networks/{networkId}/projects/{projectId}` | 授予/撤销项目网络使用权 | 仅超级管理员；有服务或默认值引用时拒绝撤销 |
| `GET /api/projects/{projectId}/networks` | 只返回该项目已授权且主节点 Docker 实际存在的网络；缺失资源不作为候选 | 超级管理员、该项目主管、该项目 `EDIT_CONFIG` 用户 |
| `GET/PUT /api/projects/{projectId}/default-network` | 读取默认 `networkId`；写入 `{networkId: number|null}`，`null` 为清除 | 超级管理员或该项目主管；只能选本项目已授权网络 |
| 服务创建/更新及详情 | `networkId: number|null`；`null` 表示无共享网络。项目默认值只在前端新建表单预选，服务保存后独立持有选择 | 有服务 `EDIT_CONFIG` 权限；网络必须属主节点、被项目授权且目标节点为本机 |

错误响应至少区分无权限、网络未登记、Docker 实体缺失、非 `bridge`、重名、仍有引用、节点与网络不匹配。创建项目后才执行项目授权和默认值设置；若后一步失败，界面准确显示已完成与未完成步骤，不把网络创建隐含进项目保存。

## 四、固定设计规则

| 主题 | 已确定的实现规则 |
| --- | --- |
| 资源归属 | 本期网络仅属主节点；数据模型用节点类型区分主节点与未来 runner，不把普通 runner ID 当作主节点标识。 |
| 访问边界 | 同一共享桥接网络中的容器可访问彼此监听端口；`expose` 不充当权限控制。网络由超级管理员授权给项目，服务只有项目获授权后才能加入。 |
| 服务数量 | 一个服务最多选择一个共享网络。任何没有 `networkId` 的旧服务保持现有 Compose 输出与调度行为。 |
| Compose | 选中网络的服务，其生成的每个子服务均同时加入私有 `default` 和 `external: true` 的 `shared`；全栈原有 `frontend -> backend` 仍可通过私有网络工作。各子服务在共享网络使用 `flowops-svc-<服务ID>-backend/frontend` 一类唯一别名。 |
| 项目默认值 | 仅作为新建服务预选；项目主管可在已授权网络内修改/清除，已存在服务不随之改变。 |
| 节点选择 | 选共享网络只能选“本机”；runner/`auto` 与网络互斥。前端联动，后端创建、更新和部署前均校验；不自动切换节点或网络。 |
| 生命周期 | 停止或删除服务容器不删除外部共享网络。managed 网络只有清空引用和 Docker 容器连接后才能删除；imported 网络取消登记时保留 Docker 实体。 |
| Docker 操作 | 后端主节点使用固定参数数组调用 `network inspect/create/rm`；网络名严格校验；数据库写入与 Docker 命令失败时按 B2/B3 处理补偿和显式错误，不伪报成功。 |

## 五、Go 配置缺陷的任务依据

`ServiceEdit.tsx` 的基础镜像和启动命令在默认收起的“高级选项” `Collapse` 内，首次展开前字段没有挂载。选择 Go 后，预设值被写进表单存储，但 `collectConfig()` 调用无参数 `form.getFieldsValue()`，只取得已注册字段；缺失的两个值又被写死的 Java 默认值补齐，同时运行时仍是 Go。面板曾展开时字段已挂载，所以保存结果随操作路径变化。前端 `uploadBinary()` 和后端上传接口只上传、登记产物，不直接修改 `serviceConfig`；上传后点击保存会经过同一错误路径。静态代码已证明该路径，具体用户操作序列由 F0 回归测试覆盖。

F0 使用完整表单存储读取值，以表单运行时字段作为唯一数据源，按当前运行时补默认值。对历史上已保存为 `runtime=go` 且镜像和命令恰为 Java 默认组合的记录，编辑页提示并提供“恢复 Go 默认”；读取时不静默改数据库，不批量覆盖用户自定义配置。

## 六、统筹验收表

| 编号 | 验收动作 | 通过条件 |
| --- | --- | --- |
| A1 | Go 服务分别在高级选项未展开、曾展开、上传二进制后、仅改其他字段后保存并重开 | 运行时 Go、镜像与命令为 Go 值；用户自定义值不丢失；Java 服务不回归 |
| A2 | 以超级管理员、项目主管、editor、普通成员直接调用网络和项目默认值接口 | 权限与第二、三节一致；前端按钮隐藏之外，后端直接拒绝越权请求 |
| A3 | 主节点创建、导入、重复创建、缺失对账、删除网络 | managed/imported 生命周期正确；使用中拒删；失败显示真实状态 |
| A4 | 两个不同 Compose 部署加入同一授权网络，通过唯一别名和容器端口互访 | 同节点互通；未授权项目无法接入；全栈内部 `backend` 代理正常 |
| A5 | 已有服务不选网络、新服务选择网络后切换到 runner/`auto` | 旧服务行为不变；不兼容选择在前端和后端均被拒绝 |
| A6 | 执行数据库迁移、后端测试、前端构建及真实 Docker 验证 | 记录命令、结果和未完成项；不以模拟客户端或页面预览替代真实 Docker 验证 |

## 七、后续阶段，不属于本期任务

子节点网络能力另立计划，再分配给 `D:\project\mix\nexa-protocol`、`D:\project\go\flowops-executor` 和后端：专用网络请求/响应、runner 受限 `inspect/create/rm`、远程结果对账及真实子节点联调。在该计划完成前，任何执行者都不得给子节点开放网络选择。跨节点通信不在本期或子节点网络操作的验收范围内。

## 八、外部依据

- Docker Compose 默认、外部与跨项目网络：https://docs.docker.com/compose/how-tos/networking/
- 用户自定义 `bridge` 网络及主机范围：https://docs.docker.com/engine/network/drivers/bridge/
- Compose 网络别名的作用域与重名行为：https://docs.docker.com/reference/compose-file/services/#aliases
