# 节点 API（本次新增）

对应后端 `NodeController`（`/api/nodes`）。涉及两类：
1. **在线节点**（`GET /api/nodes`）—— 登录即可，用于服务表单的"目标节点"下拉
2. **节点登记管理**（`/api/nodes/registry` CRUD）—— **仅超级管理员**，管理子节点注册令牌（L1 认证），前端不需要也无法看到 token 本身

## 1. 在线节点

### 1.1 节点列表

```
GET /api/nodes
```

权限：登录用户即可（普通用户也可用于服务表单"目标节点"下拉）；**未登录由 Sa-Token 拦截器返回 `code: 401`**（`msg` "未登录或登录已过期"）。部署授权由部署接口按项目权限把关，与节点列表无关。

响应 `data`：

```jsonc
[
  {
    "runnerId": "runner-1",        // 子节点唯一标识（与服务 nodeId 对应）
    "hostname": "node-01",
    "ip": "192.168.48.129",
    "version": "0.5.0",
    "lastHeartbeatTime": 1725000000000,  // 最后心跳，epoch 毫秒
    "online": true,                     // 是否在线（当前会话）
    "runningTasks": 2,                  // 最近心跳上报的进行中任务数
    "cpuUsage": 12.5,                   // CPU 使用率 %
    "memoryUsage": 30.1                 // 内存使用率 %
  }
]
```

> `lastHeartbeatTime` 为毫秒时间戳，前端展示需转格式化（`new Date(ts)`）。

### 1.2 单个节点

```
GET /api/nodes/{runnerId}
```

响应 `data`：单个 `NodeInfoVO`（同上）；节点不存在/不在线时 `code: 500`，`msg` 如"子节点不存在: xxx"。

## 2. 节点登记管理（仅超级管理员）

> 管理 `nexa_node` 注册表。**token 明文传入、服务端自动 sha256 存储**，前端/超管无需手工加密或插库。
> 非超管（或未登录）调用返回 `code: 403`，`msg`："仅超级管理员可管理节点登记"。

### 2.1 查看全部已登记节点

```
GET /api/nodes/registry
```

响应 `data`（**不返回 token 本身**，仅标记是否已配置）：

```jsonc
[
  {
    "runnerId": "runner-1",
    "nodeName": "生产节点1",       // 显示名，可空
    "status": "online",            // online / offline（随注册/心跳/断开自动更新）
    "lastHeartbeat": "2026-08-25T10:00:00",   // 最后心跳（ISO 本地时间，可空）
    "createTime": "2026-08-25T09:00:00",
    "hasToken": true               // 是否已配置注册令牌
  }
]
```

### 2.2 新增登记

```
POST /api/nodes/registry
Content-Type: application/json

{
  "runnerId": "runner-1",    // 必填，唯一
  "nodeName": "生产节点1",    // 可选
  "token": "my-secret-token" // 必填，注册令牌原文（将分发给对应子节点配置）
}
```

成功：`code: 200`，`msg` "节点已登记"。
失败：
- `runnerId` 为空 → `code: 500` "runnerId 不能为空"
- `token` 为空 → `code: 500` "token 不能为空"
- runnerId 已存在 → `code: 500` "节点已登记: xxx，如需修改请调用更新接口"

### 2.3 更新登记

```
PUT /api/nodes/registry/{runnerId}
Content-Type: application/json

{
  "nodeName": "新名字",   // 可选，传了才改
  "token": "new-token"    // 可选，传了才改（服务端重新 sha256）
}
```

成功：`code: 200`，`msg` "节点登记已更新"。
失败：节点不存在 → `code: 500` "节点未登记: xxx"。

> ⚠️ 修改 token 后，对应子节点配置必须同步更新，否则注册会被拒绝（`msg`："注册令牌无效"）。

### 2.4 删除登记

```
DELETE /api/nodes/registry/{runnerId}
```

成功：`code: 200`，`msg` "节点登记已删除"。
失败：节点不存在 → `code: 500` "节点未登记: xxx"。

> 删除登记后，该节点再次注册会被拒绝（`msg`："节点未登记，请先录入注册令牌"）。

## 3. 前端建议

- 新增 `NodeInfo` 类型（runnerId/hostname/ip/version/lastHeartbeatTime/online/runningTasks/cpuUsage/memoryUsage）与 `RegisteredNode` 类型（runnerId/nodeName/status/lastHeartbeat/createTime/hasToken）
- 服务编辑页"目标节点"下拉：选项 = 「本机（空值）」+「自动调度（auto）」+ 在线节点（`GET /api/nodes` 的 `runnerId`）
- 节点管理页（可选）：超管可见入口，展示 `GET /api/nodes/registry` 列表；新增/编辑用弹窗表单（token 输入框）；删除用确认框

## 4. 示例

```bash
# 登录（拿到 token）
curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 带 token 查询在线节点
curl http://localhost:8080/api/nodes -H "Authorization: <token>"

# 超管新增节点登记
curl -X POST http://localhost:8080/api/nodes/registry -H "Authorization: <token>" \
  -H "Content-Type: application/json" \
  -d '{"runnerId":"runner-1","nodeName":"生产节点1","token":"my-secret-token"}'

# 超管查看登记列表
curl http://localhost:8080/api/nodes/registry -H "Authorization: <token>"
```
