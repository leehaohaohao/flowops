# FlowOps 前端对接文档

> 本目录面向**前端（`D:\project\front\flowops-front`，React + TS + Ant Design）**，说明本次后端新增/变更的接口与字段。
> 后端仓库：`D:\project\backend\flowops`。

## 文档列表

| 文档 | 内容 |
|------|------|
| [node-api.md](node-api.md) | **节点相关 API（本次新增）**：在线节点列表、节点登记管理 CRUD（仅超管） |
| [node-package-api.md](node-package-api.md) | **节点发布包 API（阶段 2，P1 定稿）**：发布包上传/列表/详情、按节点分发（异步）、分发记录轮询与错误码；仅超管 |
| [service-deploy-api.md](service-deploy-api.md) | **服务/部署接口变更**：`nodeId` 字段语义、状态/日志按节点路由、上传行为说明 |

## 通用约定

### Base URL

- 开发：`http://localhost:8080`（vite dev server 已代理 `/api`、`/auth` 到后端）
- 生产/VM：由部署环境决定（如 `http://192.168.48.129:8880`）

### 登录与鉴权

| 项 | 说明 |
|----|------|
| 登录 | `POST /auth/login`，body `{"username":"admin","password":"admin123"}`，返回 `data` = token 字符串 |
| 令牌传递 | 请求头 `Authorization: <token>`（Sa-Token JWT 无状态；也兼容 Cookie，二选一即可） |
| 当前用户 | `GET /auth/info`，返回 `data.superAdmin`（boolean）——**前端据此控制超管功能显隐** |

### 统一响应格式

所有接口返回统一包装（HTTP 状态码始终为 200，业务结果看 `code`）：

```jsonc
{
  "code": 200,      // 200=成功；其他为失败
  "msg": "成功",     // 提示信息，失败时可直接展示
  "data": null      // 业务数据（可能为对象/数组/字符串）
}
```

| code | 含义 | 前端处理 |
|------|------|---------|
| 200 | 成功 | 正常渲染 `data` |
| 401 | 未登录 / 认证失败 | 跳转登录页 |
| 403 | 无权限（如非超管调用管理接口） | 提示"无权限"，隐藏入口 |
| 500 | 业务失败 | 展示 `msg` |

> 鉴权说明：
> - 所有 `/api/**`（含 `/api/nodes/**`）都经过 **Sa-Token 登录拦截器**（`SaTokenConfig`，白名单仅 `/auth/**`、静态资源等），**未登录一律返回 `code: 401`、`msg` "未登录或登录已过期"**——前端无需为节点接口额外处理，正常带 `Authorization` 即可
> - `PermissionInterceptor`（项目权限）对 `/api/nodes/**` 不设规则，放行到控制器
> - 节点登记管理 `/api/nodes/registry/**`：控制器内**仅超管**校验，已登录但非超管返回 `code: 403`、`msg` "仅超级管理员可管理节点登记"
