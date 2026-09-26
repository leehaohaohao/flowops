# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)（SemVer）规范，格式为 `Major.Minor.Patch`。

- **Major**：重大变更，由维护者主观决定
- **Minor**：新增功能特性、新增模块、模块级重构
- **Patch**：接口字段新增、小优化、Bug 修复、依赖升级

---

## 2.6.0 (2026-09-26)

### 新功能

- **节点级 Docker 网络管理**：新增 `docker_network` / `network_project_grant` / `project_default_network` 表与迁移
  （`V3_1_2__create_docker_network.sql`、`V3_1_3__add_service_network_id.sql`）；支持在主节点登记（managed 创建 /
  imported 导入）用户自定义 `bridge` 网络、按项目授权、设置项目默认网络，服务通过 `deploy_service.network_id` 接入；
  Compose 生成改为 `default` + 外部 `shared` 双网络并加唯一别名，部署前校验节点、项目授权与网络存在
  （`DockerNetworkService`、`NetworkAuthorizationService`、`NetworkController`，Docker 命令白名单化于 `DockerCommandBuilder`）。
  真实 Docker 操作与真实 HTTP/DB 集成尚未验证，仅有单元测试覆盖
- **启动配置来源表**：启动时打印每个关键配置项的「定义来源 / 占位符填充来源 / 生效值」，一眼看出参数是从
  命令行、`-e` 环境变量、`.env.<profile>` 还是 `application*.yml` 生效的；敏感项（数据库口令、`JWT_SECRET`）
  只显示来源不显示值，必填占位符未注入时直接标出缺口（`ConfigSourceReporter` / `ConfigSourceReportListener`，
  可用 `flowops.config-report.enabled=false` 关闭）

### 优化

- **部署开放主从通信端口**：`deploy-prod.sh` 增加 Nexa Protocol Master 端口映射（容器 `8081` → 宿主机 `8081`；
  `NEXA_PORT` 覆盖宿主机发布端口，`NEXA_MASTER_PORT` 覆盖容器内监听端口），并通过 `-e` 在容器内设置
  `NEXA_MASTER_HOST=0.0.0.0` 以便其他机器上的子节点连接；`Dockerfile` 的 `EXPOSE` 同步为 `8080 8081`，
  `nexa.master.port` 默认值与脚本对齐为 `8081`，部署成功提示补充放通端口与录入节点令牌的步骤
- 新增 `docs/configuration-loading-order.md`：配置来源的权威说明（解析链、逐变量降级、容器内实际来源、启动来源表与验证方法）

### 修复

- **统一配置加载链**：dotenv 改由唯一的 `DotenvPostProcessor` 作为属性源加载（`addLast`）并自行解析 profile
  （`--spring.profiles.active` → `-Dspring.profiles.active` → `SPRING_PROFILES_ACTIVE` → `prod`）。此前
  `FlowopsApplication.main` 会把 `.env.<profile>` 写成 JVM 系统属性，优先级高于 `-e`，导致挂载的 env 文件反向覆盖
  部署脚本注入的 `NEXA_MASTER_HOST` / `NEXA_MASTER_PORT`；以程序参数传 profile 时也会被忽略、始终退回加载 `.env.prod`。
  现解析顺序为命令行参数 > `-D` 系统属性 > 环境变量（`-e` / `--env-file`）> `.env.<profile>` > `application-<profile>.yml`
  > `application.yml`，逐变量降级，详见 `docs/configuration-loading-order.md`（回归测试 `DotenvLoadingOrderTest`）

## 2.5.0 (2026-09-24)

### 新功能

- **分布式主从架构**：集成 nexa-protocol Master，支持子节点注册 / 心跳 / 断开管理（`config/master/FlowOpsMasterListener`、`service/node/NodeService`）
- **远程部署**：主节点编排拆分为本机 / 远程两分支（`LocalDeployRunner` / `RemoteDeployDispatcher`），任务下发与回执落库，支持超时与节点掉线失败标记（`RemoteTaskManager`）
- **节点分配**：`deploy_service.node_id` 支持本机（空）/ 指定节点 / `auto` 最少负载自动调度
- **远程容器状态与日志查询**：协议同步查询（`QueryManager`），状态与容器日志按 nodeId 路由
- **产物标准化**：新增 `deploy_artifact` 注册表（版本 + sha256），产物经协议分块传输（`ArtifactTransferManager`），上传自动登记（jar / binary / dist），删除服务级联清理
- **子节点认证**：新增 `nexa_node` 注册表，L1 注册令牌（sha256 存储）+ L2 会话身份校验（任务回执、产物请求按会话身份鉴权）
- **节点登记管理 API（仅超级管理员）**：`GET/POST /api/nodes/registry`、`PUT/DELETE /api/nodes/registry/{runnerId}`，令牌明文录入、服务端自动加密
- **新增 flowops-docker 模块**：语义化 Docker SDK（`DockerClient` / `DefaultDockerClient`），统一封装 Docker CLI 调用
- **日志系统**：新增 `LogSource` SPI 与 `LocalDockerLogSource`；REST 日志查询接口；`/ws/logs` 文件实时跟踪
- 容器日志查询增强：支持 `since` / `until` / `timestamps` 参数
- 服务列表新增 `runningCount` 字段

### 优化

- 重连事件加固：断开归属按协议会话注册表判定，任务与查询按会话代次归因，离线快照写后复查修正，避免旧会话清理误伤新会话工作
- 容器日志获取逻辑由 `DeployExecutorService` 迁移至 `LogService`，职责收敛
- 部署脚本支持多环境（`prod` / `local`），并挂载 `.env` 文件供 `DotenvPostProcessor` 读取
- `nexa.master.host` / `port` 支持 `NEXA_MASTER_HOST` / `NEXA_MASTER_PORT` 覆盖，便于跨机器部署

### 修复

- 修复 `/api/logs/content` 将日志内容返回到 `msg` 而非 `data` 字段的问题
- 提取 `TaskSchedulerConfig` 解决 `WebSocketConfig` 循环依赖

### 移除

- 移除 HTTP 产物下载端点及其配置（产物改由协议分块传输）

### 数据库

- `V3_0_0__add_node_id.sql`：`deploy_service` / `deploy_record` 增加 `node_id`
- `V3_1_0__create_deploy_artifact.sql`：产物注册表
- `V3_1_1__create_nexa_node.sql`：子节点注册表

### 依赖

- nexa-protocol 升级至 0.6.2（注册先认证后接管、已注册消息按连接会话鉴权、断开事件所有权统一）

### 文档

- docs 目录整理与归档，新增前端对接文档目录 `docs/frontend-api/`
- 新增连接恢复与分布式联调计划、产物标准化与节点认证计划、前端对接与链接自测说明
- 新增 `AGENTS.md` 仓库指引，更新 `CLAUDE.md` 架构说明

---

## 2.4.0 (2026-06-04)

### 重构

- 配置文件生成从 `DeployExecutorService` 抽离为责任链模式（`service/generate/` 包）
- 新增 `ConfigGenerator` 接口 + `ConfigGeneratorChain`，Spring 自动发现 + `@Order` 控制执行顺序
- 新增 `DockerfileGenerator`、`NginxConfGenerator`、`ComposeYmlGenerator` 三个生成器
- 新增 `DeployContext` 上下文类，统一持有解析后的配置数据
- 新增 `YamlHelper` 静态工具类，提取 Map 安全读取、YAML 生成辅助、PortMapping 过滤等公共方法
- `DeployExecutorService` 移除约 300 行配置生成和工具方法，仅保留部署编排职责
- 删除旧 stub 文件：`ServiceConfigGenerate`、`ServiceConfigGenerateContext`、`ServiceConfig`、`DockerfileGenerate`

---

## 2.3.0 (2026-05-31)

### 新功能

- 新增运行时语言（runtime）概念，serviceConfig 中 backend/frontend 支持 `runtime` 字段
- 后端 Dockerfile 生成根据 runtime 自动适配（java / go）
- Go 运行时默认使用 `golang:1.26.3-alpine` 镜像，生成 `COPY app` + `chmod +x` 指令
- 新增 `type=binary` 上传类型，Go 二进制产物强制命名为 `app`
- 设计文档 Go 镜像版本锁定为 `golang:1.26.3-alpine`

---

## 2.2.0 (2026-05-29)

### 新功能

- 新增 `PortMapping` 实体，支持统一端口映射管理（host / expose / multiple 三种类型）
- 新增 `deploy_name` 字段，Docker Compose 项目命名与 UI 显示名称解耦
- 新增 `remark` 备注字段
- 新增数据迁移服务，支持旧数据平滑迁移至 `port_mappings` 格式

### 优化

- `port_mappings` JSON 字段替代原 `port` / `extraPorts`，统一端口配置模型
- fullstack / frontend 模式支持自定义 nginx 端口，不再硬编码 listen 80
- 服务名称增加 `deployName` 格式校验与唯一性校验
- 上传接口超时时间增大，避免大文件上传超时

### 移除

- 移除旧 `port` / `extraPorts` 字段及所有相关引用

### 文档

- 文档目录统一 `YYYY-MM-DD` 前缀命名规范

---

## 2.1.0 (2026-05-27)

### 新功能

- 服务端口支持多端口映射，可配置额外的宿主机-容器端口映射
- 新增 `extraPorts` 字段（JSON 数组格式），创建/更新服务时可传入

### 优化

- Docker Compose 生成时自动追加额外端口映射配置
- fullstack 模式下额外端口映射到 backend 容器

---

## 2.0.0 (2026-05-24)

### 新功能

- 实现项目级权限隔离，每个项目独立管理成员和角色
- 多级角色体系：viewer / operator / editor / admin / supervisor（预设角色）
- 支持「角色 + 额外权限」组合分配，灵活控制每个成员的权限粒度
- 跨项目授权（ProjectAccess），无需加入项目即可获得特定权限
- 新增 `PUT /api/users/{id}` 统一更新用户项目分配（后端 diff，原子操作）
- 新增 `GET /api/users/assignable` 查询可分配的角色和权限列表
- 新增 `GET /api/projects/{id}` 项目详情接口
- 新增容器运行日志查看与实时跟踪功能（WebSocket）

### 优化

- 权限校验统一改为基于权限码判断，不再依赖角色名硬编码
- 非超管用户只要有 MANAGE_MEMBERS 权限即可创建/管理用户
- 非超管不能分配 supervisor 角色和管理类权限（MANAGE_MEMBERS / MANAGE_PROJECTS）
- 项目拆分为三模块：flowops-common / flowops-permission / flowops-app
- 合并 ProjectGroup 与 Project 为统一模型，简化项目管理
- 所有接口返回值从 `Map<String, Object>` 替换为类型化 DTO/VO
- 全局异常处理补充 Sa-Token 认证/权限异常（401/403 返回 JSON）

### 新架构

- 前端从 Thymeleaf 模板改为 React SPA，后端提供静态文件托管
- 敏感配置改为 .env 文件注入（dotenv），支持多环境切换
- Dockerfile 适配多模块打包，.env.prod 通过 volume 挂载
- deploy-prod.sh 支持环境变量覆盖端口和 profile

---

## 1.1.0

### 新功能

- 用户登录认证（Sa-Token + JWT）
- 服务管理：创建、编辑、删除部署服务
- 部署功能：上传 JAR/dist 文件，启动/停止/重启/移除容器
- 容器状态查看和日志查看
- Dashboard 统计面板
- Docker CLI 操作（通过 ProcessBuilder 调用 docker/docker-compose）
- WebSocket 实时日志推送
- MySQL 数据持久化（MyBatis-Plus）
- Thymeleaf + Bootstrap 5 服务端渲染 UI
