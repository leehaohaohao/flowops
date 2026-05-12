# FlowOps 权限隔离系统设计

> 日期: 2026-05-12
> 状态: 待评审
> 关联: project-review-2026-05-12.md, optimization-plan.md

## 1. 背景与目标

FlowOps 当前仅有 `admin` 和 `user` 两种角色，普通用户可操作所有服务，无法满足团队多项目协作场景。

**目标**：实现项目 + 服务两级权限隔离，支持四档细粒度权限（查看/部署/编辑/删除），由超级管理员创建项目和初始账号，项目 owner 自治管理成员。

## 2. 设计方案

采用 **项目角色 + 服务 ACL 覆盖** 方案：
- 项目层面用预设角色控制默认权限
- 个别服务可通过 ACL 覆盖特定用户的权限
- 超级管理员（admin）全局绕过，可操作所有资源

## 3. 数据模型

### 3.1 新增表

#### `project` 项目表

```sql
CREATE TABLE project (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255),
    owner_id    BIGINT NOT NULL,
    is_default  TINYINT(1) DEFAULT 0,
    create_time DATETIME DEFAULT NOW(),
    update_time DATETIME DEFAULT NOW()
);

-- 默认项目，不可删除，兜底旧数据
INSERT INTO project (name, description, owner_id, is_default)
VALUES ('默认项目', '未分组服务归属此项目', 1, 1);
```

#### `project_member` 项目成员表

```sql
CREATE TABLE project_member (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id  BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    role        VARCHAR(20) NOT NULL,
    create_time DATETIME DEFAULT NOW(),
    UNIQUE KEY uk_project_user (project_id, user_id)
);

-- admin 自动成为默认项目 owner
INSERT INTO project_member (project_id, user_id, role)
VALUES (1, 1, 'owner');
```

#### `service_acl` 服务权限覆盖表

```sql
CREATE TABLE service_acl (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    service_id  BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    permissions VARCHAR(50) NOT NULL,
    create_time DATETIME DEFAULT NOW(),
    UNIQUE KEY uk_service_user (service_id, user_id)
);
```

### 3.2 现有表改动

```sql
ALTER TABLE deploy_service ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1;
```

现有服务自动归属默认项目（`project_id=1`），新建服务必须指定项目。

### 3.3 角色权限映射（代码中定义，不建表）

| 角色 | 查看 | 部署 | 编辑配置 | 删除 |
|------|------|------|----------|------|
| owner | Y | Y | Y | Y |
| editor | Y | Y | Y | N |
| deployer | Y | Y | N | N |
| viewer | Y | N | N | N |

## 4. 权限校验流程

```
请求进来
  │
  ▼
Sa-Token 登录校验（现有逻辑不变）
  │
  ▼
PermissionInterceptor（新增）
  │  提取 resource_type + resource_id
  │  查询有效权限，无权限返回 403
  ▼
Controller → Service（业务逻辑中做二次校验）
```

### 4.1 权限查询优先级

```
1. admin 角色 → 全局超级管理员，直接放行
2. service_acl 表有记录 → 用 ACL 权限
3. 无 ACL → 查 project_member 表的项目角色 → 映射四档权限
4. 都没有 → 无权限，返回 403
```

### 4.2 各接口最低权限要求

| 接口 | 最低权限 |
|------|----------|
| GET /api/services/list | view（按项目过滤） |
| GET /api/deploy/status/{id} | view |
| GET /api/logs/* | view |
| POST /api/deploy/start/{id} | deploy |
| POST /api/deploy/stop/{id} | deploy |
| POST /api/deploy/restart/{id} | deploy |
| PUT /api/services/{id} | edit |
| POST /api/deploy/upload/{id} | edit |
| DELETE /api/services/{id} | delete |
| POST /api/services/create | edit（项目 editor 以上可创建） |

## 5. API 设计

### 5.1 项目管理（admin 专属）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/projects/list | 项目列表 |
| POST | /api/projects/create | 创建项目（指定 owner） |
| PUT | /api/projects/{id} | 编辑项目信息 |
| DELETE | /api/projects/{id} | 删除项目（不可删默认项目） |

### 5.2 项目成员管理（项目 owner 可操作）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/projects/{id}/members | 成员列表 |
| POST | /api/projects/{id}/members | 添加成员（指定角色） |
| PUT | /api/projects/{id}/members/{userId} | 修改成员角色 |
| DELETE | /api/projects/{id}/members/{userId} | 移除成员 |

### 5.3 服务 ACL 覆盖（项目 owner 可操作）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/services/{id}/acl | 查看服务 ACL |
| PUT | /api/services/{id}/acl | 设置/修改服务 ACL |
| DELETE | /api/services/{id}/acl/{userId} | 清除某用户的 ACL |

## 6. 页面改动

| 页面 | 改动 |
|------|------|
| dashboard | 按项目分组展示，支持项目切换 |
| service-list | 左侧增加项目树/筛选，只显示有权限的服务 |
| service-edit | 创建服务时必须选择所属项目 |
| 新增 project-list | admin 专属，项目 CRUD |
| 新增 project-detail | 项目 owner 管理成员、查看服务列表 |
| 新增 service-acl | 服务详情页内嵌，管理单个服务 ACL 覆盖 |

## 7. 边界情况处理

### 7.1 项目删除
- 默认项目（`is_default=1`）禁止删除
- 删除前项目内服务必须先移走或删除，否则返回提示："该项目下仍有 N 个服务，请先迁移或删除"

### 7.2 成员管理
- 项目至少保留一个 owner
- 移除最后一个 owner 时拒绝，提示"请先指定新 owner"
- admin 创建项目时指定的 owner 自动写入 `project_member` 表

### 7.3 权限变更即时生效
- 每次请求实时查询 `project_member` + `service_acl`，不做缓存
- 修改角色或 ACL 后下一次请求立即生效，不依赖 token 过期
- 后续性能优化可加本地缓存 + 变更时清除

### 7.4 用户删除
- 同步清理 `project_member` 和 `service_acl` 中的关联记录
- 若该用户是某项目唯一 owner，拒绝删除或要求先转让

### 7.5 服务迁移项目
- 编辑服务时可修改 `project_id`，将服务迁移到其他项目
- ACL 覆盖跟着服务走，迁移后保留不变

### 7.6 兼容性
- 无项目归属的旧数据通过默认项目（`project_id=1`）兜底，用户体验无感
- 现有 admin 用户功能完全保留，全局绕过权限约束
