# Changelog

## 2.1.0 (2026-05-27)

### 功能增强

- 服务端口支持多端口映射，可配置额外的宿主机-容器端口映射
- 新增 `extraPorts` 字段（JSON 数组格式），创建/更新服务时可传入
- Docker Compose 生成时自动追加额外端口映射配置
- fullstack 模式下额外端口映射到 backend 容器

---

## 2.0.0 (2026-05-24)

### 权限系统重构

- 实现项目级权限隔离，每个项目独立管理成员和角色
- 多级角色体系：viewer / operator / editor / admin / supervisor（预设角色）
- 支持「角色 + 额外权限」组合分配，灵活控制每个成员的权限粒度
- 跨项目授权（ProjectAccess），无需加入项目即可获得特定权限
- 权限校验统一改为基于权限码判断，不再依赖角色名硬编码
- 非超管用户只要有 MANAGE_MEMBERS 权限即可创建/管理用户
- 非超管不能分配 supervisor 角色和管理类权限（MANAGE_MEMBERS / MANAGE_PROJECTS）

### 架构升级

- 项目拆分为三模块：flowops-common / flowops-permission / flowops-app
- 合并 ProjectGroup 与 Project 为统一模型，简化项目管理
- 前端从 Thymeleaf 模板改为 React SPA，后端提供静态文件托管
- 敏感配置改为 .env 文件注入（dotenv），支持多环境切换

### API 改进

- 所有接口返回值从 Map<String, Object> 替换为类型化 DTO/VO
- 新增 `PUT /api/users/{id}` 统一更新用户项目分配（后端 diff，原子操作）
- 新增 `GET /api/users/assignable` 查询可分配的角色和权限列表
- 新增 `GET /api/projects/{id}` 项目详情接口
- 新增容器运行日志查看与实时跟踪功能（WebSocket）
- 全局异常处理补充 Sa-Token 认证/权限异常（401/403 返回 JSON）

### 部署优化

- Dockerfile 适配多模块打包，.env.prod 通过 volume 挂载
- deploy-prod.sh 支持环境变量覆盖端口和 profile

---

## 1.1.0

### 基础功能

- 用户登录认证（Sa-Token + JWT）
- 服务管理：创建、编辑、删除部署服务
- 部署功能：上传 JAR/dist 文件，启动/停止/重启/移除容器
- 容器状态查看和日志查看
- Dashboard 统计面板
- Docker CLI 操作（通过 ProcessBuilder 调用 docker/docker-compose）
- WebSocket 实时日志推送
- MySQL 数据持久化（MyBatis-Plus）
- Thymeleaf + Bootstrap 5 服务端渲染 UI
