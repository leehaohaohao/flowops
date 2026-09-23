# 服务 / 部署接口变更（本次）

本次后端改动对前端可见的变化集中在三处：**服务实体的 `nodeId` 字段**（需前端表单/列表接入）、**状态与容器日志按节点路由**（响应结构不变，取值新增）、**上传/删除行为**（接口不变，无感）。

> 历史遗留清理提醒（2026-08-11 计划步骤 6，前端待做）：`DeployService` 类型中 `projectName` / `port` / `extraPorts` 三个字段后端已不返回，应删除（页面标题项目名走 `getProject()`，不受影响）。

## 1. 服务 `nodeId` 字段（创建/更新/列表）

### 1.1 接口

| 接口 | 变更 |
|------|------|
| `POST /api/services/create` | body 新增可选字段 `nodeId` |
| `PUT /api/services/{id}` | body 新增可选字段 `nodeId` |
| `GET /api/services/list`、`GET /api/services/{id}` | 返回项新增 `nodeId` 字段 |

### 1.2 取值语义（后端已实现，部署/状态/日志均按其路由）

| `nodeId` 值 | 含义 | 部署行为 |
|-------------|------|---------|
| 空 / null | **本机执行**（默认） | docker 操作在主节点本地执行 |
| `"auto"` | **自动调度** | 从在线节点中选"最少负载"（心跳上报任务数最少）的一个；无在线节点时报错 |
| 其他字符串 | **指定节点** | 下发到该 `runnerId`；节点不在线时报错 |

创建/编辑示例：

```jsonc
// POST /api/services/create
{
  "name": "订单服务",
  "deployName": "order-service",
  "projectId": 1,
  "serviceType": "backend",
  "serviceConfig": "{...}",
  "nodeId": "runner-1"   // 或 ""（本机）/ "auto"（自动调度），可省略
}
```

### 1.3 前端建议

- `DeployService` 类型加 `nodeId?: string`；`CreateServiceRequest` / `UpdateServiceRequest` payload 加 `nodeId`
- `ServiceEdit` 基础信息区加「目标节点」下拉：
  - 选项：`本机`（提交空串）、`自动调度`（提交 `"auto"`）、在线节点（来自 `GET /api/nodes` 的 `runnerId`）
  - 编辑回显：`service.nodeId`
- `ServiceList` 可选加「节点」列：空 → "本机"，`auto` → "自动调度"，否则显示 runnerId

## 2. 状态查询（按节点路由）

```
GET /api/deploy/status/{serviceId}
```

响应 `data`：

```jsonc
{
  "running": true,
  "status": "running"
}
```

`status` 取值说明（**新增了 `offline` / `unknown` 两种兜底值，前端展示需兼容**）：

| 场景 | status |
|------|--------|
| 本机执行，容器正常 | running / stopped |
| 远程节点上报 | running / stopped / restarting / exited 等（子节点解析 `docker compose ps`） |
| 指定节点**离线** | `offline`（`running=false`） |
| 远程查询**超时/失败** | `unknown`（`running=false`） |

## 3. 容器日志（按节点路由，非流式）

```
GET /api/logs/container/{serviceId}?tail=500&since=&until=&timestamps=false
```

- 响应 `data` 为**日志字符串**（`Result.ok(null, data)`，注意 `msg` 为 null，读 `data`）
- 远程节点服务：后端下发 `CONTAINER_LOGS_REQ` 走子节点非流式 tail，`tail`/`since`/`until`/`timestamps` 透传
- ⚠️ **失败时 `data` 里是错误提示文本**（非 HTTP 错误），例如：
  - `"目标节点不在线: runner-1"`
  - `"无在线子节点，无法获取远程容器日志"`
  - `"远程日志查询失败或超时: node=runner-1"`
  - `"暂无日志"`（正常但无内容）
  - 前端可将 `data` 直接当文本展示，无需特殊区分（本机行为不变）

## 4. 上传与删除（行为变化，接口不变）

| 接口 | 说明 |
|------|------|
| `POST /api/deploy/upload/{serviceId}?type=jar\|binary` | 上传成功后自动登记产物注册表（版本 +1），响应不变 |
| `POST /api/deploy/upload-dist/{serviceId}` | 解压后自动登记 DIST 产物（版本 +1），响应不变 |
| `DELETE /api/services/{id}` | 额外级联删除产物注册表记录（存储文件逻辑不变），响应不变 |

> 对前端**无感**：接口路径、请求/响应结构均未变，仅后端多了一步产物登记。同一服务重复上传会生成新版本，远程部署拉取最新版本。

## 5. 部署/容器操作

`POST /api/deploy/start|stop|restart|remove/{serviceId}`：接口与响应不变（`Result<Void>`，看 `msg`）；后端按 `nodeId` 自动路由本机执行或远程任务下发，前端无需感知。
