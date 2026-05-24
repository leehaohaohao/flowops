# 前端接口变更通知

> 后端已完成「项目组」与「项目」的合并。原 `/api/groups` 相关接口已全部移除，功能合并到 `/api/projects`。请前端尽快适配。

---

## 一、删除的接口

以下接口已不存在，调用会返回 404：

| 旧接口 | 说明 |
|--------|------|
| `POST /api/groups` | 创建项目组 |
| `GET /api/groups` | 列出项目组 |
| `GET /api/groups/{id}` | 获取项目组详情 |
| `PUT /api/groups/{id}` | 更新项目组 |
| `DELETE /api/groups/{id}` | 删除项目组 |
| `GET /api/groups/{groupId}/members` | 列出组成员 |
| `POST /api/groups/{groupId}/members` | 添加组成员 |
| `PUT /api/groups/{groupId}/members/{userId}` | 更新成员角色 |
| `DELETE /api/groups/{groupId}/members/{userId}` | 移除成员 |

---

## 二、变更的接口

### 1. `GET /api/projects` — 列出项目

**旧行为：** 返回 `List<Project>`，支持 `?groupId=xxx` 筛选
**新行为：** 返回 `List<Map>`，含成员数、创建时间等，不再需要 `groupId` 参数

```json
// 新返回格式
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "name": "默认项目",
      "description": "系统默认项目",
      "isDefault": 1,
      "memberCount": 3,
      "createTime": "2026-05-08T17:13:50"
    }
  ]
}
```

**前端改动：**
- 移除 `?groupId=xxx` 查询参数
- 适配新的返回字段（`isDefault`, `memberCount`, `createTime`）

---

### 2. `GET /api/projects/{id}` — 获取项目详情

**新返回格式：**

```json
{
  "code": 200,
  "data": {
    "id": 1,
    "name": "默认项目",
    "description": "系统默认项目",
    "isDefault": 1,
    "memberCount": 3,
    "createTime": "2026-05-08T17:13:50"
  }
}
```

---

### 3. `POST /api/projects` — 创建项目

**旧请求体：**
```json
{ "groupId": 1, "name": "xxx", "description": "xxx" }
```

**新请求体：**
```json
{ "name": "xxx", "description": "xxx" }
```

**前端改动：** 移除 `groupId` 字段。

---

### 4. 成员管理接口 — URL 路径变更

所有成员管理接口从 `/api/groups/{groupId}/members` 移到 `/api/projects/{projectId}/members`：

| 旧路径 | 新路径 |
|--------|--------|
| `GET /api/groups/{groupId}/members` | `GET /api/projects/{projectId}/members` |
| `POST /api/groups/{groupId}/members` | `POST /api/projects/{projectId}/members` |
| `PUT /api/groups/{groupId}/members/{userId}` | `PUT /api/projects/{projectId}/members/{userId}` |
| `DELETE /api/groups/{groupId}/members/{userId}` | `DELETE /api/projects/{projectId}/members/{userId}` |

请求体和返回格式不变。

---

### 5. `POST /api/users/create` — 创建用户

**旧请求体：**
```json
{
  "username": "zhangsan",
  "password": "123456",
  "groupId": 1,
  "roleId": 2
}
```

**新请求体：**
```json
{
  "username": "zhangsan",
  "password": "123456",
  "projectId": 1,
  "roleId": 2
}
```

**前端改动：** `groupId` → `projectId`。不传则自动归入默认项目。

---

### 6. `GET /api/users/list` — 用户列表

**前端改动：** 无接口变化，但内部逻辑改为按项目过滤（非按组）。

---

### 7. `GET /api/auth/info` — 当前用户信息

**旧返回：**
```json
{
  "groups": [
    { "id": 1, "name": "默认项目组", "roleName": "admin", "isSupervisor": true }
  ]
}
```

**新返回：**
```json
{
  "projects": [
    { "id": 1, "name": "默认项目", "roleName": "admin", "isSupervisor": true }
  ]
}
```

**前端改动：**
- `data.groups` → `data.projects`
- 字段含义不变，只是名称从"项目组"变为"项目"

---

## 三、未变更的接口

以下接口不受影响：

- `POST /api/auth/login` — 登录
- `GET /api/services/list` — 服务列表
- `POST /api/services/create` — 创建服务（`projectId` 字段不变）
- `POST /api/deploy/start/{id}` — 部署
- 所有 `/api/deploy/*` 接口
- `POST /api/access` / `GET /api/access` / `DELETE /api/access` — 跨项目授权
- `GET /api/roles` / `POST /api/roles` — 角色管理
- `DELETE /api/users/{id}` — 删除用户

---

## 四、适配检查清单

- [ ] 移除所有 `/api/groups` 相关的 API 调用
- [ ] 成员管理 URL 从 `/api/groups/{id}/members` 改为 `/api/projects/{id}/members`
- [ ] `POST /api/users/create` 请求体 `groupId` → `projectId`
- [ ] `GET /api/projects` 移除 `?groupId` 参数，适配新返回字段
- [ ] `POST /api/projects` 请求体移除 `groupId`
- [ ] `GET /api/auth/info` 中 `data.groups` → `data.projects`
- [ ] 项目列表页面展示 `isDefault` 标记和 `memberCount`
- [ ] 默认项目不可删除（`isDefault=1` 时隐藏删除按钮）
