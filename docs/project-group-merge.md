# ProjectGroup 与 Project 合并方案

## 背景

原数据模型中 `project_group`（项目组）和 `project`（项目）是两个独立实体，存在一对多关系。但实际业务中一个项目组只对应一个项目，两者是同一个概念。合并后：**创建项目 = 创建项目组**，成员直接挂在项目下。

## 合并前后对比

### 数据模型

```
合并前:                                合并后:
project_group                          project (统一)
  ├── id, name, description              ├── id, name, description
  ├── is_default                         ├── is_default
  └── project (子表)                     └── (无子表，project 即是"组")
        ├── id, group_id (FK)
        └── name, description

group_member.group_id → project_group.id    group_member.project_id → project.id
deploy_service.project_id → project.id      (不变)
project_access.project_id → project.id      (不变)
```

### 实体变化

| 实体 | 变化 |
|------|------|
| `Project` | 移除 `groupId` 字段，新增 `isDefault` 字段 |
| `GroupMember` | `groupId` → `projectId` |
| `ProjectGroup` | **删除** |
| `ProjectGroupMapper` | **删除** |
| `ProjectGroupService` | **删除**，方法合并到 `ProjectService` |
| `ProjectGroupController` | **删除**，端点合并到 `ProjectController` |
| `GroupRequest` (DTO) | **删除** |

### 注解变化

| 旧注解 | 新注解 |
|--------|--------|
| `@RequireGroupSupervisor("groupId")` | `@RequireProjectSupervisor("projectId")` |
| `@RequireGroupSupervisor("project:id")` | `@RequireProjectSupervisor("id")` |

## 删除的文件（6 个）

```
flowops-permission/.../entity/ProjectGroup.java
flowops-permission/.../mapper/ProjectGroupMapper.java
flowops-permission/.../service/ProjectGroupService.java
flowops-permission/.../controller/ProjectGroupController.java
flowops-permission/.../dto/GroupRequest.java
flowops-common/.../RequireGroupSupervisor.java
```

## 新增的文件（1 个）

```
flowops-common/.../RequireProjectSupervisor.java
```

## 修改的文件（14 个）

| 文件 | 改动说明 |
|------|----------|
| `Project.java` | 移除 `groupId`，新增 `isDefault` |
| `GroupMember.java` | `groupId` → `projectId` |
| `CreateProjectRequest.java` | 移除 `groupId` 字段 |
| `CreateUserRequest.java` | `groupId` → `projectId` |
| `ProjectService.java` | 吸收 `getDefaultProject()`, `getMemberCount()`, `delete()` 含 isDefault 检查 |
| `ProjectController.java` | 吸收列表返回（含 memberCount/isDefault/createTime），删除端点含 isDefault 保护 |
| `MemberService.java` | 所有 `groupId` → `projectId`，新增 `getUserProjectIds()` |
| `MemberController.java` | URL 从 `/api/groups/{groupId}/members` → `/api/projects/{projectId}/members` |
| `PermissionService.java` | 移除 `ProjectGroupMapper`，`isSupervisor()` 直接用 projectId，`getUserGroups()` → `getUserProjects()`，`getVisibleProjectIds()` 简化 |
| `PermissionAspect.java` | 移除 `ProjectMapper`，supervisor 检查不再需要 project→group 反查 |
| `PermissionInterceptor.java` | URL 拦截模式更新 |
| `UserService.java` | `ProjectGroupService` → `ProjectService` |
| `UserController.java` | 同上，`getUserGroups()` → `getUserProjects()` |
| `AuthController.java` | `getUserGroups()` → `getUserProjects()`，返回字段 `groups` → `projects` |

## 权限解析流程变化

### 合并前

```
1. 用户请求 → 解析 projectId
2. 通过 project.group_id 找到 groupId
3. 通过 group_member(groupId, userId) 找到成员记录
4. 获取角色权限
```

### 合并后

```
1. 用户请求 → 解析 projectId
2. 直接通过 group_member(project_id, userId) 找到成员记录
3. 获取角色权限
```

少了一次 project → project_group 的反查，逻辑更简洁。

## 数据库迁移

已有数据库需执行以下 SQL：

```sql
-- 1. project 表添加 is_default 列
ALTER TABLE project ADD COLUMN is_default TINYINT(1) NOT NULL DEFAULT 0;
UPDATE project SET is_default = 1 WHERE id = 1;

-- 2. group_member.group_id 改名为 project_id
ALTER TABLE group_member CHANGE COLUMN group_id project_id BIGINT NOT NULL;
ALTER TABLE group_member DROP INDEX uk_group_user;
ALTER TABLE group_member ADD UNIQUE KEY uk_project_user (project_id, user_id);

-- 3. 删除 project_group 表（数据已合并到 project）
DROP TABLE IF EXISTS project_group;

-- 4. 删除 project.group_id 列
ALTER TABLE project DROP COLUMN group_id;
ALTER TABLE project DROP INDEX idx_group_id;
```

## 验证

```bash
mvn clean compile                    # 三模块编译通过
mvn clean package -pl flowops-app -DskipTests  # 打包
```
