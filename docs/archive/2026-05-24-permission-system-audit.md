# FlowOps 权限系统设计文档

> 审核日期：2026/05/24

---

## 一、权限架构总览

系统采用**四层防护**：

| 层级 | 机制 | 作用域 |
|------|------|--------|
| **Layer A** | Sa-Token 登录拦截器 | 所有请求（除登录等白名单）必须登录 |
| **Layer B** | `PermissionInterceptor` | `/api/**` 的 URL 模式匹配兜底 |
| **Layer C** | `PermissionAspect`（AOP） | 处理 `@RequirePermission` 和 `@RequireGroupSupervisor` 注解 |
| **Layer D** | `StpInterfaceImpl` | 实现 Sa-Token 的 `getPermissionList`/`getRoleList` |

**执行顺序：** A（登录校验） → B（拦截器检查，有注解则跳过） → C（注解精确校验）

---

## 二、数据模型

### 新增 7 张表

| 表 | 用途 |
|----|------|
| `project_group` | 项目组（顶层组织） |
| `project` | 项目（归属项目组） |
| `group_member` | 用户-项目组-角色关系 + 额外权限 |
| `perm_definition` | 权限码定义 |
| `perm_role` | 角色（预设 + 自定义） |
| `role_permission` | 角色-权限映射 |
| `project_access` | 跨组项目授权 |

### 修改的表

- `sys_user` 新增 `is_super_admin TINYINT(1) DEFAULT 0`
- `deploy_service` 新增 `project_id BIGINT DEFAULT 1`

### 关键字段

- `group_member.extra_permissions`：额外权限码，逗号分隔（如 `"UPLOAD,DELETE"`），与角色权限取并集
- `sys_user.is_super_admin`：1 = 超级管理员，0 = 普通用户
- `sys_user.role`：保留兼容字段，不再参与权限判断

---

## 三、权限码 & 预设角色

### 9 个权限码

| 权限码 | 含义 | 适用场景 |
|--------|------|----------|
| `VIEW` | 查看 | 查看服务列表、状态、日志 |
| `DEPLOY` | 部署 | 部署/重启服务 |
| `START` | 启动 | 启动容器 |
| `STOP` | 停止 | 停止容器 |
| `UPLOAD` | 上传 | 上传 JAR 包、前端 dist |
| `EDIT_CONFIG` | 编辑配置 | 创建、编辑服务配置 |
| `DELETE` | 删除 | 删除服务、移除容器 |
| `MANAGE_MEMBERS` | 管理成员 | 添加/移除/修改组内成员（主管专属） |
| `MANAGE_PROJECTS` | 管理项目 | 创建/编辑/删除项目（主管专属） |

### 5 个预设角色

| 角色 | VIEW | DEPLOY | START | STOP | UPLOAD | EDIT_CONFIG | DELETE | MANAGE_MEMBERS | MANAGE_PROJECTS |
|------|:----:|:------:|:-----:|:----:|:------:|:-----------:|:------:|:--------------:|:---------------:|
| viewer | ✓ | | | | | | | | |
| operator | ✓ | ✓ | ✓ | ✓ | | | | | |
| editor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | | |
| admin | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | |
| supervisor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

### 角色层级

1. **超级管理员** (`is_super_admin = 1`)：全局绕过，拥有全部权限
2. **主管**：`group_member.role_id` 指向 `supervisor` 角色，可管理组内成员和项目
3. **普通成员**：根据被分配的角色 + 额外权限操作组内项目

---

## 四、权限查询算法

```
getEffectivePermissions(userId, projectId):
  1. is_super_admin? → 返回全部 9 个权限码
  2. 查 project.group_id → group_member 获取 role_id → role_permission 获取角色权限
  3. 合并 group_member.extra_permissions（逗号分隔 CSV）
  4. 合并 project_access（跨组授权的权限）
  5. 返回并集
```

**数据可见性规则：**
- 用户看到自己所在项目组的所有项目
- 跨组授权的用户还能看到被授权的特定项目
- 超级管理员看到所有

---

## 五、注解说明

### `@RequirePermission`

资源级权限检查。解析 `projectId` 后查询用户在该项目中的有效权限。

```java
@RequirePermission("DEPLOY")                                    // 从 URL {id} 反查 projectId
@RequirePermission(value = "EDIT_CONFIG", projectId = "params.projectId")  // 从 DTO 字段读取
```

**解析规则：**
- `projectId` 为空：从 URL 路径变量 `{id}` 取 serviceId，反查 `deploy_service.project_id`
- `projectId = "params.xxx"`：从 `@RequestBody` DTO 的 `xxx` 字段读取

### `@RequireGroupSupervisor`

检查当前用户是否为指定项目组的主管（或超级管理员）。

```java
@RequireGroupSupervisor("groupId")              // 从 URL 路径变量读取
@RequireGroupSupervisor("params.groupId")        // 从 DTO 字段读取
@RequireGroupSupervisor("project:id")            // 从 DTO 的 id 字段反查项目的 groupId
```

---

## 六、逐接口权限审计

### AuthController

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| POST | `/auth/login` | 无（公开） | ✅ 正确 |
| POST | `/auth/logout` | Sa-Token 登录检查 | ✅ 正确 |
| GET | `/auth/info` | Sa-Token 登录检查 | ✅ 正确 |

### ServiceController

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| GET | `/api/services/list` | 拦截器放行 + Controller 按可见项目过滤 | ⚠️ GAP-1 |
| GET | `/api/services/{id}` | `@RequirePermission("VIEW")` | ✅ 正确 |
| POST | `/api/services/create` | `@RequirePermission("EDIT_CONFIG", projectId="params.projectId")` | ✅ 正确 |
| PUT | `/api/services/{id}` | `@RequirePermission("EDIT_CONFIG")` | ✅ 正确 |
| DELETE | `/api/services/{id}` | `@RequirePermission("DELETE")` | ✅ 正确 |

### DeployController

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| POST | `/api/deploy/start/{id}` | `@RequirePermission("DEPLOY")` | ✅ 正确 |
| POST | `/api/deploy/stop/{id}` | `@RequirePermission("STOP")` | ✅ 正确 |
| POST | `/api/deploy/restart/{id}` | `@RequirePermission("DEPLOY")` | ✅ 正确 |
| POST | `/api/deploy/remove/{id}` | `@RequirePermission("DELETE")` | ✅ 正确 |
| POST | `/api/deploy/upload/{id}` | `@RequirePermission("UPLOAD")` | ✅ 正确 |
| POST | `/api/deploy/upload-dist/{id}` | `@RequirePermission("UPLOAD")` | ✅ 正确 |
| GET | `/api/deploy/status/{id}` | `@RequirePermission("VIEW")` | ✅ 正确 |
| GET | `/api/deploy/logs/{id}` | `@RequirePermission("VIEW")` | ✅ 正确 |

### UserController

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| GET | `/api/users/list` | 无注解，无拦截器匹配 | ⚠️ GAP-2 |
| POST | `/api/users/create` | 方法体内手动检查 | ⚠️ GAP-3 |
| DELETE | `/api/users/{id}` | `@SaCheckRole("super_admin")` | ✅ 正确 |

### ProjectGroupController

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| POST | `/api/groups` | `@SaCheckRole("super_admin")` | ✅ 正确 |
| GET | `/api/groups` | 无注解，无拦截器 | ⚠️ GAP-4 |
| GET | `/api/groups/{id}` | 无注解，无拦截器 | ⚠️ GAP-4 |
| PUT | `/api/groups/{id}` | `@SaCheckRole("super_admin")` | ✅ 正确 |
| DELETE | `/api/groups/{id}` | `@SaCheckRole("super_admin")` | ✅ 正确 |

### ProjectController

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| POST | `/api/projects` | `@RequireGroupSupervisor("params.groupId")` | ✅ 正确 |
| GET | `/api/projects` | 拦截器始终放行 + Controller 按可见项目过滤 | ⚠️ GAP-5 |
| GET | `/api/projects/{id}` | 无注解，拦截器始终放行 | 🔴 GAP-6 |
| PUT | `/api/projects/{id}` | `@RequireGroupSupervisor("project:id")` | ✅ 正确 |
| DELETE | `/api/projects/{id}` | `@RequireGroupSupervisor("project:id")` | ✅ 正确 |

### MemberController

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| GET | `/api/groups/{gid}/members` | `@RequireGroupSupervisor("groupId")` | ✅ 正确 |
| POST | `/api/groups/{gid}/members` | `@RequireGroupSupervisor("groupId")` | ✅ 正确 |
| PUT | `/api/groups/{gid}/members/{uid}` | `@RequireGroupSupervisor("groupId")` | ✅ 正确 |
| DELETE | `/api/groups/{gid}/members/{uid}` | `@RequireGroupSupervisor("groupId")` | ✅ 正确 |

### RoleController

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| GET | `/api/roles/presets` | 无 | ⚠️ GAP-7 |
| GET | `/api/roles` | 无 | ⚠️ GAP-7 |
| GET | `/api/roles/{id}/permissions` | 无 | ⚠️ GAP-7 |
| POST | `/api/roles` | 无 | 🔴 GAP-8 |
| PUT | `/api/roles/{id}` | 无 | 🔴 GAP-8 |
| DELETE | `/api/roles/{id}` | 无 | 🔴 GAP-8 |

### PermissionController（跨组授权）

| 方法 | 路径 | 保护机制 | 状态 |
|------|------|----------|------|
| POST | `/api/access/grant` | `@SaCheckRole("super_admin")` | ✅ 正确 |
| GET | `/api/access` | `@SaCheckRole("super_admin")` | ✅ 正确 |
| DELETE | `/api/access/{id}` | `@SaCheckRole("super_admin")` | ✅ 正确 |

---

## 七、发现的问题

### GAP-8 [CRITICAL] — RoleController 写接口无权限校验

`POST/PUT/DELETE /api/roles` **完全没有权限保护**。任何登录用户都可以：
- 创建拥有 `MANAGE_MEMBERS`、`DELETE` 等高权限的自定义角色
- 修改现有角色的权限
- 删除角色

**这是权限提升漏洞。** 应改为：主管只能在自己组内创建角色，超级管理员可操作所有角色。

### GAP-6 [HIGH] — `GET /api/projects/{id}` 无可见性校验

任何登录用户可以通过枚举 ID 查看任意项目详情（名称、描述、所属组）。应添加可见性检查，仅允许查看自己所属组的项目或被跨组授权的项目。

### ISSUE-B [MEDIUM] — Fail-open 设计

`PermissionAspect` 和 `PermissionInterceptor` 在获取当前用户为 null 或抛出异常时**放行**而非拒绝。应改为 fail-closed（拒绝请求）。

### GAP-2 [MEDIUM] — `GET /api/users/list` 无注解保护

Controller 内部有逻辑过滤（主管看组内、超管看全部），但缺少注解声明。如果 Controller 逻辑变更，无兜底保护。

### GAP-3 [MEDIUM] — `POST /api/users/create` 手动检查

权限检查写在方法体内部而非注解，不够零侵入。应改用 `@RequireGroupSupervisor` 注解。

### GAP-1 [LOW] — `GET /api/services/list` 未按 VIEW 权限过滤

拦截器明确放行此路径。Controller 按可见项目过滤，但不检查用户在每个项目中是否有 `VIEW` 权限。仅拥有 `DEPLOY` 权限的用户也能看到服务列表。

### GAP-4 [LOW] — `GET /api/groups` 和 `GET /api/groups/{id}` 信息泄露

任何登录用户可查看任意项目组详情（成员数、项目数）。Controller 按成员关系过滤列表，但 `getById` 无可见性检查。

### GAP-5 [LOW] — `GET /api/projects` 拦截器始终放行

拦截器的 `checkProjectManagement` 方法对所有 HTTP 方法返回 `true`。Controller 按可见项目过滤，但缺少注解声明。

### GAP-7 [LOW] — `GET /api/roles/*` 读接口无保护

预设角色列表、角色权限等对任何登录用户可见。如果前端需要展示角色选项，可接受；但应明确记录。

---

## 八、建议修复优先级

| 优先级 | 问题 | 修复方案 |
|--------|------|----------|
| **P0 立即** | GAP-8 RoleController 无权限 | 加 `@RequireGroupSupervisor` 或 `@SaCheckRole` 注解 |
| **P0 立即** | GAP-6 项目详情无可见性检查 | 加可见性校验注解或拦截器逻辑 |
| **P0 立即** | ISSUE-B Fail-open | Aspect/Interceptor 异常时拒绝请求 |
| **P1 尽快** | GAP-2 UserController 无注解 | 加 `@SaCheckRole` 注解 |
| **P1 尽快** | GAP-3 手动检查 | 改为 `@RequireGroupSupervisor` 注解 |
| **P2 迭代** | GAP-1/4/5/7 读接口 | 补充注解明确意图 |
