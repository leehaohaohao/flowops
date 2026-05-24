# FlowOps 权限系统 — 前端对接文档

## 概述

FlowOps 权限系统采用**项目组 → 项目 → 服务**三级结构，前端需要根据用户权限动态控制 UI 元素的显示/隐藏。

**核心概念：**
- **超级管理员** (`isSuperAdmin: true`)：拥有全部权限，可管理项目组、用户、跨组授权
- **主管** (`isSupervisor: true`)：项目组负责人，可管理组内成员、项目
- **普通成员**：根据被分配的角色拥有不同权限

## 统一响应格式

所有 API 返回统一结构：

```json
{
  "code": 200,       // 200=成功, 400=业务错误, 401=未登录, 403=权限不足
  "msg": "描述信息",
  "data": {}         // 业务数据，类型因接口而异
}
```

## 认证方式

请求头携带 JWT Token：

```
Authorization: <token>
```

Token 通过 `/auth/login` 获取，存储在 localStorage。

---

## 一、认证接口

### 1.1 登录

```
POST /auth/login
```

**请求体：**
```json
{
  "username": "admin",
  "password": "admin123"
}
```

**成功响应：**
```json
{
  "code": 200,
  "msg": "登录成功",
  "data": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9..."
}
```

`data` 就是 JWT Token，前端存入 localStorage，后续请求放入 `Authorization` 头。

### 1.2 获取当前用户信息（核心接口）

```
GET /auth/info
```

**响应（普通用户）：**
```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "username": "zhangsan",
    "isSuperAdmin": false,
    "groups": [
      {
        "id": 1,
        "name": "前端组",
        "roleName": "editor",
        "isSupervisor": false
      },
      {
        "id": 3,
        "name": "运维组",
        "roleName": "supervisor",
        "isSupervisor": true
      }
    ],
    "projectPermissions": {
      "1": ["VIEW", "DEPLOY", "START", "STOP", "UPLOAD", "EDIT_CONFIG"],
      "3": ["VIEW", "DEPLOY"],
      "5": ["VIEW"]
    }
  }
}
```

**响应（超级管理员）：**
```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "username": "admin",
    "isSuperAdmin": true,
    "groups": []
  }
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `username` | string | 用户名 |
| `isSuperAdmin` | boolean | 是否超级管理员 |
| `groups` | array | 用户所属的项目组列表 |
| `groups[].id` | number | 项目组 ID |
| `groups[].name` | string | 项目组名称 |
| `groups[].roleName` | string | 在该组中的角色名 |
| `groups[].isSupervisor` | boolean | 是否为该组的主管 |
| `projectPermissions` | object | 项目权限映射（超级管理员无此字段） |
| `projectPermissions[projectId]` | string[] | 该用户在该项目中拥有的权限码列表 |

### 1.3 退出

```
POST /auth/logout
```

---

## 二、权限码定义

| 权限码 | 含义 | 适用场景 |
|--------|------|----------|
| `VIEW` | 查看 | 查看服务列表、状态、日志 |
| `DEPLOY` | 部署 | 启动、重启服务 |
| `START` | 启动 | 启动容器 |
| `STOP` | 停止 | 停止容器 |
| `UPLOAD` | 上传 | 上传 JAR 包、前端 dist |
| `EDIT_CONFIG` | 编辑配置 | 创建、编辑服务配置 |
| `DELETE` | 删除 | 删除服务、移除容器 |
| `MANAGE_MEMBERS` | 管理成员 | 添加/移除/修改组内成员（主管专属） |
| `MANAGE_PROJECTS` | 管理项目 | 创建/编辑/删除项目（主管专属） |

**预设角色权限对照：**

| 角色 | VIEW | DEPLOY | START | STOP | UPLOAD | EDIT_CONFIG | DELETE | MANAGE_MEMBERS | MANAGE_PROJECTS |
|------|:----:|:------:|:-----:|:----:|:------:|:-----------:|:------:|:--------------:|:---------------:|
| viewer | ✓ | | | | | | | | |
| operator | ✓ | ✓ | ✓ | ✓ | | | | | |
| editor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | | |
| admin | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | |
| supervisor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

---

## 三、前端权限判断逻辑

### 3.1 权限判断流程

```typescript
// 1. 获取用户信息
const userInfo = await api('/auth/info');

// 2. 判断是否有某个服务的操作权限
function hasPermission(service: Service, permCode: string): boolean {
  // 超级管理员拥有全部权限
  if (userInfo.isSuperAdmin) return true;

  // 从 service.projectId 查 projectPermissions
  const perms = userInfo.projectPermissions?.[service.projectId];
  if (!perms) return false;

  return perms.includes(permCode);
}

// 3. 判断是否为某组的主管
function isSupervisor(groupId: number): boolean {
  if (userInfo.isSuperAdmin) return true;
  return userInfo.groups?.some(g => g.id === groupId && g.isSupervisor) ?? false;
}
```

### 3.2 服务列表中的权限控制

`GET /api/services/list` 返回的每个服务对象包含 `projectId` 字段，前端根据它匹配权限：

```json
{
  "id": 1,
  "name": "my-app",
  "projectId": 1,
  "projectName": "默认项目",
  "status": "running",
  ...
}
```

**按钮显示/隐藏示例：**

```html
<!-- 部署按钮 -->
<button v-if="hasPermission(service, 'DEPLOY')">部署</button>

<!-- 停止按钮 -->
<button v-if="hasPermission(service, 'STOP')">停止</button>

<!-- 上传按钮 -->
<button v-if="hasPermission(service, 'UPLOAD')">上传</button>

<!-- 编辑按钮 -->
<button v-if="hasPermission(service, 'EDIT_CONFIG')">编辑</button>

<!-- 删除按钮 -->
<button v-if="hasPermission(service, 'DELETE')">删除</button>
```

### 3.3 菜单/页面可见性

```typescript
// 超级管理员可见：项目组管理、用户管理、跨组授权
if (userInfo.isSuperAdmin) {
  showGroupManagement();
  showUserManagement();
  showCrossGroupAccess();
}

// 主管可见：成员管理、项目管理
if (userInfo.groups?.some(g => g.isSupervisor)) {
  showMemberManagement();
  showProjectManagement();
}

// 所有登录用户可见：服务列表（后端已过滤，只返回有权限的服务）
showServiceList();
```

### 3.4 403 错误处理

当权限不足时，后端返回：

```json
{
  "code": 403,
  "msg": "权限不足",
  "data": null
}
```

前端应在全局请求拦截器中统一处理 403，显示提示信息。

---

## 四、项目组管理接口（超级管理员）

### 4.1 创建项目组

```
POST /api/groups
```

**请求体：**
```json
{
  "name": "前端组",
  "description": "负责前端项目开发"
}
```

### 4.2 获取项目组列表

```
GET /api/groups
```

**响应：**
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "name": "默认项目组",
      "description": "系统自动创建",
      "createdAt": "2026-05-22T10:00:00"
    }
  ]
}
```

> 超级管理员返回全部，普通用户只返回自己所在的项目组。

### 4.3 获取项目组详情

```
GET /api/groups/{id}
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "name": "前端组",
    "description": "负责前端项目开发",
    "memberCount": 5,
    "projectCount": 3
  }
}
```

### 4.4 编辑项目组

```
PUT /api/groups/{id}
```

**请求体：**
```json
{
  "name": "前端开发组",
  "description": "新描述"
}
```

### 4.5 删除项目组

```
DELETE /api/groups/{id}
```

> 删除前需确保组内无项目，否则返回业务错误。

---

## 五、项目管理接口（超级管理员 + 主管）

### 5.1 创建项目

```
POST /api/projects
```

**请求体：**
```json
{
  "groupId": 1,
  "name": "用户中心",
  "description": "用户认证与权限管理"
}
```

> 超级管理员可创建任意组的项目，主管只能创建自己负责组的项目。

### 5.2 获取项目列表

```
GET /api/projects
GET /api/projects?groupId=1
```

**响应：**
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "groupId": 1,
      "name": "默认项目",
      "description": "系统自动创建",
      "createdAt": "2026-05-22T10:00:00"
    }
  ]
}
```

> 超级管理员看全部（可按 groupId 过滤），普通用户只看自己有权限的项目。

### 5.3 获取项目详情

```
GET /api/projects/{id}
```

### 5.4 编辑项目

```
PUT /api/projects/{id}
```

**请求体：**
```json
{
  "name": "新名称",
  "description": "新描述"
}
```

### 5.5 删除项目

```
DELETE /api/projects/{id}
```

> 删除前需确保项目下无服务。

---

## 六、成员管理接口（主管 + 超级管理员）

### 6.1 获取组成员列表

```
GET /api/groups/{groupId}/members
```

**响应：**
```json
{
  "code": 200,
  "data": [
    {
      "userId": 2,
      "username": "zhangsan",
      "roleId": 4,
      "roleName": "editor",
      "joinedAt": "2026-05-22T10:00:00"
    }
  ]
}
```

### 6.2 添加成员

```
POST /api/groups/{groupId}/members
```

**请求体：**
```json
{
  "userId": 5,
  "roleId": 3
}
```

> `roleId` 可以是预设角色或自定义角色的 ID。

### 6.3 修改成员角色

```
PUT /api/groups/{groupId}/members/{userId}
```

**请求体：**
```json
{
  "roleId": 4
}
```

### 6.4 移除成员

```
DELETE /api/groups/{groupId}/members/{userId}
```

---

## 七、角色管理接口

### 7.1 获取预设角色列表

```
GET /api/roles/presets
```

**响应：**
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "name": "viewer",
      "description": "只读权限",
      "isPreset": true
    },
    {
      "id": 2,
      "name": "operator",
      "description": "运维操作",
      "isPreset": true
    }
  ]
}
```

### 7.2 获取某组可用角色

```
GET /api/roles?groupId=1
```

返回预设角色 + 该组的自定义角色。

### 7.3 获取角色的权限列表

```
GET /api/roles/{id}/permissions
```

**响应：**
```json
{
  "code": 200,
  "data": ["VIEW", "DEPLOY", "START", "STOP"]
}
```

### 7.4 创建自定义角色

```
POST /api/roles
```

**请求体：**
```json
{
  "name": "qa-tester",
  "groupId": 1,
  "description": "测试人员角色",
  "permissions": ["VIEW", "DEPLOY", "START", "STOP"]
}
```

> 自定义角色绑定到特定项目组，只有该组的主管和超级管理员可以创建。

### 7.5 编辑自定义角色

```
PUT /api/roles/{id}
```

**请求体：**
```json
{
  "name": "qa-tester-v2",
  "description": "更新描述",
  "permissions": ["VIEW", "DEPLOY", "START", "STOP", "UPLOAD"]
}
```

### 7.6 删除自定义角色

```
DELETE /api/roles/{id}
```

---

## 八、跨组授权接口（超级管理员）

### 8.1 授权用户访问其他组的项目

```
POST /api/access/grant
```

**请求体：**
```json
{
  "userId": 5,
  "projectId": 3,
  "permCodes": ["VIEW", "DEPLOY"]
}
```

> 授予用户 5 对项目 3 的查看和部署权限，即使用户不在项目 3 所属的项目组中。

### 8.2 查看用户的跨组授权

```
GET /api/access?userId=5
```

**响应：**
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "userId": 5,
      "projectId": 3,
      "permCodes": ["VIEW", "DEPLOY"],
      "createdAt": "2026-05-22T10:00:00"
    }
  ]
}
```

### 8.3 撤销跨组授权

```
DELETE /api/access/{id}
```

---

## 九、服务管理接口（已有，权限增强）

### 9.1 服务列表

```
GET /api/services/list
```

**变更：**
- 后端自动按用户可见项目过滤，只返回有权限的服务
- 返回对象新增 `projectId` 和 `projectName` 字段

**响应示例：**
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "name": "user-center",
      "projectId": 1,
      "projectName": "默认项目",
      "status": "running",
      "port": 8081,
      "volumeDir": "/data/flowops/services/user-center"
    }
  ]
}
```

### 9.2 创建服务

```
POST /api/services/create
```

**请求体：**（新增 `projectId` 字段）
```json
{
  "name": "new-service",
  "projectId": 1,
  "port": 8082,
  ...
}
```

> 需要目标项目的 `EDIT_CONFIG` 权限。

### 9.3 其他服务接口

| 接口 | 所需权限 |
|------|----------|
| `GET /api/services/{id}` | VIEW |
| `PUT /api/services/{id}` | EDIT_CONFIG |
| `DELETE /api/services/{id}` | DELETE |

---

## 十、部署接口（已有，权限增强）

所有部署接口现在需要对应权限，权限不足返回 403。

| 接口 | 所需权限 |
|------|----------|
| `POST /api/deploy/start/{serviceId}` | DEPLOY |
| `POST /api/deploy/stop/{serviceId}` | STOP |
| `POST /api/deploy/restart/{serviceId}` | DEPLOY |
| `POST /api/deploy/remove/{serviceId}` | DELETE |
| `POST /api/deploy/upload/{serviceId}` | UPLOAD |
| `POST /api/deploy/upload-dist/{serviceId}` | UPLOAD |
| `GET /api/deploy/status/{serviceId}` | VIEW |
| `GET /api/deploy/logs/{serviceId}` | VIEW |

---

## 十一、完整对接流程

### 11.1 应用启动时

```typescript
// 1. 检查 token 是否存在
const token = localStorage.getItem('token');
if (!token) {
  router.push('/login');
  return;
}

// 2. 获取用户信息和权限
const userInfo = await api('/auth/info');

// 3. 存储到全局状态（Pinia / Context / Redux）
store.setUser(userInfo);

// 4. 根据权限动态生成菜单
const menus = buildMenus(userInfo);
```

### 11.2 构建动态菜单

```typescript
function buildMenus(userInfo: UserInfo) {
  const menus = [];

  // 所有用户可见
  menus.push({ path: '/dashboard', label: '仪表盘' });
  menus.push({ path: '/services', label: '服务列表' });

  // 超级管理员专属
  if (userInfo.isSuperAdmin) {
    menus.push({ path: '/groups', label: '项目组管理' });
    menus.push({ path: '/users', label: '用户管理' });
    menus.push({ path: '/access', label: '跨组授权' });
  }

  // 主管可见
  if (userInfo.isSuperAdmin || userInfo.groups?.some(g => g.isSupervisor)) {
    menus.push({ path: '/members', label: '成员管理' });
    menus.push({ path: '/projects', label: '项目管理' });
  }

  return menus;
}
```

### 11.3 渲染服务列表时

```typescript
// 服务对象从 /api/services/list 获取，已按权限过滤
services.forEach(service => {
  // 根据 service.projectId 查权限
  const perms = getPermissionsForProject(service.projectId);

  service.canDeploy = perms.includes('DEPLOY');
  service.canStop = perms.includes('STOP');
  service.canUpload = perms.includes('UPLOAD');
  service.canEdit = perms.includes('EDIT_CONFIG');
  service.canDelete = perms.includes('DELETE');
  service.canViewLogs = perms.includes('VIEW');
});
```

### 11.4 权限不足时的降级处理

```typescript
// API 请求拦截器
api.interceptors.response.use(
  response => {
    if (response.data.code === 403) {
      message.error('权限不足，请联系管理员');
      return Promise.reject(response.data);
    }
    if (response.data.code === 401) {
      localStorage.removeItem('token');
      router.push('/login');
      return Promise.reject(response.data);
    }
    return response;
  }
);
```

---

## 十二、注意事项

1. **路由建议使用 Hash 模式**（`/#/dashboard`），避免浏览器刷新时请求 `/dashboard` 等路径被后端当成 API 返回 404。Spring Boot 只托管 `/`、`/index.html` 和 `/assets/**` 静态资源。

2. **超级管理员不需要 `projectPermissions`**：`/auth/info` 对超级管理员不返回此字段，前端通过 `isSuperAdmin` 判断即可，拥有全部权限。

3. **服务列表已自动过滤**：`GET /api/services/list` 只返回用户有权限看到的服务，前端无需再过滤。

4. **权限码是字符串**：注意是 `"DEPLOY"` 不是 `"deploy"`，大小写敏感。

5. **`projectPermissions` 的 key 是字符串**：JSON 对象的 key 始终是字符串类型，做数字比较时需要转换：`projectPermissions[String(service.projectId)]`。

6. **WebSocket 无权限校验**：当前 `/ws/logs` 和 `/ws/container-logs` 不走权限拦截，建议前端在调用 WebSocket 前先检查对应服务的 VIEW 权限。
