# FlowOps 权限隔离系统设计文档

> 日期: 2026-05-22
> 状态: 已实现

## Context

FlowOps 当前仅有 `admin`/`user` 两种角色，普通用户可操作所有服务，无法满足团队多项目协作需求。本计划实现项目组 + 项目的两级权限隔离，支持超级管理员 → 主管 → 成员的角色层级，以及跨组项目授权。

前端使用独立的 React 项目，本文档只涉及后端 API 和权限系统。

## 数据模型

### 新增 7 张表

| 表名 | 用途 |
|------|------|
| `project_group` | 项目组（顶层组织单元） |
| `project` | 项目（归属项目组，服务挂在项目下） |
| `group_member` | 组成员关系（用户-项目组-角色） |
| `perm_definition` | 权限定义（固定词汇表：VIEW, DEPLOY, START, STOP, UPLOAD, EDIT_CONFIG, DELETE） |
| `perm_role` | 角色（预设 + 自定义，关联一组权限） |
| `role_permission` | 角色-权限多对多映射 |
| `project_access` | 跨组项目授权（超级管理员给用户单独授权其他组的项目） |

### 修改现有表

- `sys_user` 新增 `is_super_admin TINYINT(1) DEFAULT 0`
- `deploy_service` 新增 `project_id BIGINT DEFAULT 1`

### 数据迁移

- 自动创建"默认项目组"和"默认项目"
- 现有 admin 用户标记为 `is_super_admin = 1`
- 现有非 admin 用户加入默认项目组（viewer 角色）
- 现有服务归属默认项目
- 迁移通过 `DataMigrationService` 启动时自动执行

## 角色层级

**三级角色体系：**

1. **超级管理员** (`is_super_admin = 1`) — 全局绕过，可创建项目组、创建账号、分配主管、跨组授权
2. **主管** — 通过 `group_member` 表分配 `supervisor` 预设角色，一个组可有多个主管。可管理组内成员、分配角色、创建/编辑/删除组内项目
3. **普通成员** — 根据被分配的角色操作组内项目

**预设角色（快捷分配）：**

| 角色 | VIEW | DEPLOY | START | STOP | UPLOAD | EDIT_CONFIG | DELETE | 管理成员 | 管理项目 |
|------|------|--------|-------|------|--------|-------------|--------|----------|----------|
| viewer | Y | | | | | | | | |
| operator | Y | Y | Y | Y | | | | | |
| editor | Y | Y | Y | Y | Y | Y | | | |
| admin | Y | Y | Y | Y | Y | Y | Y | | |
| supervisor | Y | Y | Y | Y | Y | Y | Y | Y | Y |

支持自定义角色（主管可创建，绑定到特定项目组）。

## 权限查询算法

```
getEffectivePermissions(userId, projectId):
  1. is_super_admin? -> 返回全部权限
  2. 查 project.group_id -> 查 group_member 获取 role -> 查 role_permission 获取 ROLE_PERMS
  3. 查 project_access 获取 CROSS_PERMS（跨组授权）
  4. 有效权限 = ROLE_PERMS ∪ CROSS_PERMS
```

**数据可见性规则：**
- 用户看到自己所在项目组的所有项目
- 跨组授权的用户还能看到被授权的特定项目
- 超级管理员看到所有

## 权限校验机制

**策略：自定义 `PermissionInterceptor` + Sa-Token `@SaCheckRole`**

- 超级管理员专属接口用 `@SaCheckRole("super_admin")`
- 主管专属接口（成员管理、项目管理）用 `PermissionInterceptor` 检查 `supervisor` 角色
- 资源级操作（部署/编辑/删除服务）用 `PermissionInterceptor` 从 URL 解析 serviceId -> projectId -> 校验权限
- 注册在 `SaTokenConfig`，拦截 `/api/**`

**URL -> 权限映射：**

| URL 模式 | 方法 | 所需权限 |
|----------|------|----------|
| `/api/deploy/start/{id}` | POST | DEPLOY |
| `/api/deploy/stop/{id}` | POST | STOP |
| `/api/deploy/restart/{id}` | POST | DEPLOY |
| `/api/deploy/remove/{id}` | POST | DELETE |
| `/api/deploy/upload/{id}` | POST | UPLOAD |
| `/api/deploy/status/{id}` | GET | VIEW |
| `/api/deploy/logs/{id}` | GET | VIEW |
| `/api/services/{id}` | GET | VIEW |
| `/api/services/{id}` | PUT | EDIT_CONFIG |
| `/api/services/{id}` | DELETE | DELETE |
| `/api/services/create` | POST | EDIT_CONFIG |

## API 设计

### 项目组管理（超级管理员）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/groups` | 创建项目组 |
| GET | `/api/groups` | 项目组列表（超级管理员看全部，普通用户看自己的） |
| GET | `/api/groups/{id}` | 项目组详情（含成员数、项目数） |
| PUT | `/api/groups/{id}` | 编辑项目组 |
| DELETE | `/api/groups/{id}` | 删除项目组（需先清空项目） |

### 项目管理（主管 + 超级管理员）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/projects` | 创建项目（body: groupId, name, description） |
| GET | `/api/projects?groupId={id}` | 项目列表 |
| GET | `/api/projects/{id}` | 项目详情 |
| PUT | `/api/projects/{id}` | 编辑项目 |
| DELETE | `/api/projects/{id}` | 删除项目（需先清空服务） |

### 成员管理（主管 + 超级管理员）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/groups/{groupId}/members` | 成员列表 |
| POST | `/api/groups/{groupId}/members` | 添加成员（body: userId, roleId） |
| PUT | `/api/groups/{groupId}/members/{userId}` | 修改成员角色 |
| DELETE | `/api/groups/{groupId}/members/{userId}` | 移除成员 |

### 角色管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/roles/presets` | 预设角色列表 |
| GET | `/api/roles?groupId={id}` | 可用角色列表（预设 + 该组自定义） |
| GET | `/api/roles/{id}/permissions` | 角色的权限列表 |
| POST | `/api/roles` | 创建自定义角色（body: name, groupId, permissions[]） |
| PUT | `/api/roles/{id}` | 编辑自定义角色 |
| DELETE | `/api/roles/{id}` | 删除自定义角色 |

### 跨组授权（超级管理员）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/access/grant` | 授权（body: userId, projectId, permCodes[]） |
| GET | `/api/access?userId={id}` | 查看用户的跨组授权 |
| DELETE | `/api/access/{id}` | 撤销授权 |

### 认证信息

**`GET /auth/info` 返回：**

```json
{
  "code": 200,
  "data": {
    "username": "zhangsan",
    "isSuperAdmin": false,
    "groups": [
      {"id": 1, "name": "默认项目组", "isSupervisor": false, "roleName": "editor"}
    ],
    "projectPermissions": {
      "1": ["VIEW", "DEPLOY", "START", "STOP", "UPLOAD", "EDIT_CONFIG"],
      "3": ["VIEW", "DEPLOY"]
    }
  }
}
```

- `projectPermissions` 是 `projectId -> 权限列表` 的映射
- 超级管理员不返回此字段（前端通过 `isSuperAdmin` 判断，拥有全部权限）
- 前端渲染服务列表时，根据 `service.projectId` 查 `projectPermissions[projectId]` 得到该服务的操作权限，动态显示/隐藏按钮

### 现有接口改动

- `GET /api/services/list` — 按用户可见项目过滤，返回含 `projectId` 的服务对象
- `POST /api/services/create` — 需要 EDIT_CONFIG 权限 + `projectId` 参数
- `DeployController` 所有接口 — 通过 PermissionInterceptor 补充权限校验
- `GET /api/users/list` — `@SaCheckRole("super_admin")`

## 实现文件清单

### 修改的文件

| 文件 | 改动 |
|------|------|
| `entity/SysUser.java` | 新增 `isSuperAdmin` 字段 |
| `entity/DeployService.java` | 新增 `projectId` 字段 |
| `config/StpInterfaceImpl.java` | 实现 `getPermissionList()` 返回用户有效权限 |
| `config/SaTokenConfig.java` | 注册 PermissionInterceptor |
| `controller/AuthController.java` | `/auth/info` 返回 groups + projectPermissions |
| `controller/ServiceController.java` | 服务列表按可见项目过滤，创建时校验 EDIT_CONFIG |
| `controller/UserController.java` | 改为 `@SaCheckRole("super_admin")` |
| `service/ServiceMgmtService.java` | 新增 `listByProjectIds()`，创建服务需 projectId |
| `service/DashboardService.java` | 统计按可见项目过滤 |

### 新增的文件

| 文件 | 说明 |
|------|------|
| `entity/ProjectGroup.java` | 项目组实体 |
| `entity/Project.java` | 项目实体 |
| `entity/GroupMember.java` | 组成员实体 |
| `entity/PermDefinition.java` | 权限定义实体 |
| `entity/PermRole.java` | 角色实体 |
| `entity/RolePermission.java` | 角色权限映射实体 |
| `entity/ProjectAccess.java` | 跨组授权实体 |
| `mapper/ProjectGroupMapper.java` | 项目组 Mapper |
| `mapper/ProjectMapper.java` | 项目 Mapper |
| `mapper/GroupMemberMapper.java` | 组成员 Mapper |
| `mapper/PermDefinitionMapper.java` | 权限定义 Mapper |
| `mapper/PermRoleMapper.java` | 角色 Mapper |
| `mapper/RolePermissionMapper.java` | 角色权限 Mapper |
| `mapper/ProjectAccessMapper.java` | 跨组授权 Mapper |
| `service/PermissionService.java` | 权限解析核心服务 |
| `service/ProjectGroupService.java` | 项目组 CRUD |
| `service/ProjectService.java` | 项目 CRUD |
| `service/RoleService.java` | 角色 CRUD |
| `service/MemberService.java` | 成员管理 |
| `service/DataMigrationService.java` | 启动时自动数据迁移 |
| `config/PermissionInterceptor.java` | URL 级权限拦截器 |
| `controller/ProjectGroupController.java` | 项目组管理 API |
| `controller/ProjectController.java` | 项目管理 API |
| `controller/MemberController.java` | 成员管理 API |
| `controller/RoleController.java` | 角色管理 API |
| `controller/PermissionController.java` | 跨组授权 API |
| `sql/migration.sql` | 数据库迁移脚本 |

## 后续优化（Phase 5）

- WebSocket 连接认证
- 权限查询结果本地缓存（60s TTL）
