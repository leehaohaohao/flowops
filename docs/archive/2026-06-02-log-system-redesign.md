# 日志系统重构 + 项目列表优化 设计方案

> 日期：2026-06-02
> 状态：待审批

---

## 一、背景与问题

### 1.1 日志系统现状

- 所有部署日志存储在 `/data/flowops/services/logs/` 扁平目录下，无项目/服务维度区分
- 文件命名 `{serviceName}-{毫秒时间戳}.log`，不直观，难以按日期检索
- `DeployRecord.logPath` 字段存在但从未赋值，部署记录与日志文件无关联
- `LogController` 和 `LogWebSocketHandler` 无路径遍历防护，无权限校验
- `logBasePath` 在 `LogService`、`LogWebSocketHandler`、`DeployExecutorService` 三处硬编码
- `ContainerLogWebSocketHandler` 未使用 `DockerUtil`，直接 `new ProcessBuilder("docker"...)`
- 前端"日志查看"页面一股脑展示所有日志文件，无筛选能力
- 服务列表的"日志"按钮跳转到 ContainerLogs 页面，加载日志按钮无请求发出

### 1.2 项目列表现状

- 项目以表格形式展示，只能点击项目名称文字链接跳转到服务列表
- 缺少项目统计信息的直观展示（服务数、运行数、成员数）
- 无"上次访问"快捷入口

### 1.3 分布式改造前瞻

当前为单体架构，后续计划拆分为 master（调度）+ 子节点（部署执行）的分布式架构。日志系统的设计需确保后续拆分时日志功能无需大面积重写。

---

## 二、日志系统设计

### 2.1 日志存储结构

```
/data/flowops/logs/
├── {projectId}/
│   └── {serviceId}/
│       ├── deploy/                      ← 部署日志（docker compose up 输出，系统自动写入）
│       │   ├── 2026-06-02.log
│       │   ├── 2026-06-02-2.log        ← 超过 100MB 时拆分
│       │   └── 2026-06-01.log
│       └── app/                         ← 应用日志（用户通过 volume 映射挂载，log4j/logback 写入）
│           ├── app.log                  ← 日志文件名由用户的应用日志配置决定
│           ├── app.2026-06-01.log
│           └── ...
```

**日志类型说明：**

| 类型 | 来源 | 存储方式 | 用途 |
|------|------|---------|------|
| 部署日志 (deploy) | `docker compose up -d --build` 的 stdout/stderr | 系统自动追加写入 `deploy/{date}.log` | 排查构建和部署失败 |
| 应用日志 (app) | 容器内 log4j/logback 写入的日志文件 | **用户自行配置 Docker Volume 映射**，将容器内日志目录挂载到宿主机 `app/` 目录 | 查看历史业务日志（跨容器重启持久化） |
| 运行日志 (runtime) | `docker compose logs --follow` 的实时输出 | **不落盘**，WebSocket 直接转发 | 实时监控容器运行状态 |

**应用日志 Volume 映射说明：**

系统在生成 docker-compose.yml 时，根据服务配置**自动添加 volume 映射**：

- **容器内路径**：由用户在创建/编辑服务时指定（如 `/app/logs`），存储在 `serviceConfig.appLogPath` 中
- **宿主机路径**：系统自动按 `/data/flowops/logs/{projectId}/{serviceId}/app` 规则生成
- 用户的应用（如 Spring Boot + logback）需配置日志输出到容器内指定的路径

```yaml
# docker-compose.yml（系统自动生成）
services:
  my-app:
    volumes:
      # 宿主机路径由系统按项目/服务自动拼接，容器内路径由用户指定
      - /data/flowops/logs/1/3/app:/app/logs
```

日志文件名由用户的应用日志框架配置决定（如 logback 的文件命名策略），系统不做干预，只读取宿主机 `app/` 目录下所有日志文件。

**系统不执行 `docker cp`，不自动拷贝日志文件。** 应用日志的持久化完全依赖系统自动配置的 volume 映射。

**文件命名规则：**

- 格式：`{date}.log`，如 `2026-06-02.log`
- 拆分：单文件超过 100MB 时，新建 `{date}-2.log`、`{date}-3.log`...
- 同一天的部署输出追加到同一文件（`StandardOpenOption.APPEND`），不同天写不同文件

### 2.2 后端 API 重构

#### LogController (`/api/logs`)

| 方法 | 路径 | 参数 | 说明 | 权限 |
|------|------|------|------|------|
| GET | `/api/logs/list` | `serviceId`, `type`(deploy/app), `date`(可选) | 列出指定服务的某类日志文件 | `VIEW` |
| GET | `/api/logs/content` | `serviceId`, `type`, `filename`, `offset`, `limit` | 读取日志文件内容 | `VIEW` |
| GET | `/api/logs/dates` | `serviceId`, `type` | 返回该服务有日志的日期列表 | `VIEW` |

#### DeployController (`/api/deploy`)

| 方法 | 路径 | 参数 | 说明 | 权限 |
|------|------|------|------|------|
| GET | `/api/deploy/logs/{serviceId}` | `tail` | 获取容器运行日志（保持不变） | `VIEW` |

#### WebSocket 端点

| 端点 | 协议 | 说明 |
|------|------|------|
| `/ws/logs` | 客户端发送 JSON: `{"serviceId":1, "type":"deploy", "filename":"2026-06-02.log"}` | 订阅指定日志文件的增量推送 |
| `/ws/container-logs` | 客户端发送 JSON: `{"serviceId":1, "tail":500, "follow":true}` | 实时跟踪容器 stdout（保持不变） |

### 2.3 后端代码改动

#### 新增 / 重构

| 文件 | 改动 |
|------|------|
| `LogService.java` | 重构：基于 `serviceId` + `type` + `date` 查询日志；`logBasePath` 从配置注入；增加路径遍历校验；增加日志文件大小检测与拆分逻辑 |
| `LogController.java` | 重构：新接口，全部加 `@RequirePermission("VIEW")`，参数校验 |
| `LogWebSocketHandler.java` | 重构：接收 JSON 参数（serviceId/type/filename），路径遍历校验，`logBasePath` 从配置注入 |
| `ContainerLogWebSocketHandler.java` | 修复：使用 `DockerUtil.newProcessBuilder()` 替代直接 `new ProcessBuilder` |
| `DeployExecutorService.java` | 改动：`deploy()` 方法日志输出改为追加到 `logs/{projectId}/{serviceId}/deploy/{date}.log`；补上 `record.setLogPath()` 赋值；生成 docker-compose.yml 时自动添加应用日志 volume 映射 |
| `entity/DeployService.java` | 无改动，`appLogPath` 存储在 `serviceConfig` JSON 中 |
| `WebSocketConfig.java` | 小改：`LogWebSocketHandler` 改为 `@Component` 注入（与 `ContainerLogWebSocketHandler` 一致） |

#### 配置

`application.yml` 增加：

```yaml
app:
  storage:
    path: /data/flowops/services
  logs:
    path: /data/flowops/logs
    max-file-size: 104857600    # 100MB
```

### 2.4 分布式预留设计

日志系统的核心接口设计不依赖本地文件系统路径，后续拆分时只需替换数据源：

```
现在（单体）:                          未来（分布式）:

┌─────────────┐                     ┌─────────────┐
│  LogService  │                     │  LogService  │  ← 不变
│  (调度层)    │                     │  (调度层)    │
└──────┬──────┘                     └──────┬──────┘
       │                                    │
  ┌────┴────┐                          ┌────┴────┐
  │LogSource │ (接口)                  │LogSource │ (同一个接口)
  └────┬────┘                          └────┬────┘
       │                              ┌─────┴──────┐
  ┌────┴──────┐                ┌──────┴───┐  ┌─────┴──────┐
  │LocalDocker│                │LocalDocker│  │RemoteNode  │
  │LogSource  │                │LogSource  │  │LogSource   │
  └───────────┘                └──────────┘  └────────────┘
```

- **查询接口基于 serviceId**：不暴露文件路径给前端，子节点只需实现同样的查询协议
- **WebSocket 协议标准化**：统一使用 JSON `{type, msg, timestamp}` 格式
- **`LogSource` 接口**：定义 `listFiles(serviceId, type, date)`、`readContent(serviceId, type, filename, offset, limit)`、`tailFollow(serviceId, type)` 三个方法

```java
public interface LogSource {
    List<String> listFiles(Long serviceId, String type, String date);
    String readContent(Long serviceId, String type, String filename, long offset, long limit);
    // tailFollow 通过 WebSocket 实现，不在 HTTP 接口中
}
```

当前实现 `LocalDockerLogSource`，后续分布式时新增 `RemoteNodeLogSource` 通过 HTTP/WebSocket 从子节点拉取日志，`LogService` 代码无需修改。

---

## 三、前端设计

### 3.1 项目列表 — 卡片布局

**改动文件：** `ProjectList.tsx`

**布局：**
- 顶部"上次访问"快捷入口条：显示最近访问的项目名，点击直接跳转服务列表（localStorage 存储 `lastVisitedProjectId`）
- 卡片网格（`Row + Col`，每行 2-3 个）：每个项目一张卡片，包含：
  - 项目名称（大字加粗）
  - 项目描述（灰色小字，单行省略）
  - 统计信息行：服务数 / 运行中数 / 成员数
- 点击卡片任意位置跳转到服务列表
- 卡片右上角保留"管理"按钮（跳转成员管理）

**路由变更：** 无，仍为 `/projects` → `/projects/:projectId/services`

### 3.2 日志查看页面 — 筛选栏布局

**改动文件：** `DeployLogs.tsx`（重写）

**页面结构：**

```
┌─────────────────────────────────────────────────┐
│  项目: [下拉框▼]  服务: [下拉框▼]               │
│  类型: [部署日志] [应用日志]   日期: [日期选择器] │
├──────────────┬──────────────────────────────────┤
│  日志文件列表 │  日志内容区                       │
│              │                                  │
│  · 2026-06-02│  [10:23:15] Building image...    │
│  · 2026-06-02│  [10:23:18] Step 1/8...          │
│    (续)      │  [10:23:22] Container started    │
│  · 2026-06-01│                                  │
│              │                                  │
│              │  [工具栏: 实时跟踪 | 下载 | 清空]  │
├──────────────┴──────────────────────────────────┤
│  状态栏: 加载完成 | 正在跟踪... | 连接已关闭     │
└─────────────────────────────────────────────────┘
```

**交互流程：**

1. 页面加载 → 调用项目列表 API 填充项目下拉框
2. 选择项目 → 调用服务列表 API（按 projectId 筛选）填充服务下拉框
3. 选择服务 → 调用 `GET /api/logs/dates?serviceId=X&type=deploy` 填充日期列表，默认选中最新日期
4. 选择日期 → 调用 `GET /api/logs/list?serviceId=X&type=deploy&date=Y` 填充文件列表
5. 点击文件 → WebSocket `/ws/logs` 订阅该文件，内容区实时显示
6. 切换到"应用日志" Tab → 同样流程，type 改为 `app`

**实时跟踪模式：** 内容区底部工具栏有"实时跟踪"按钮，点击后连接 `/ws/container-logs`，在当前内容区追加实时输出。

### 3.3 服务列表日志按钮 — 抽屉（Drawer）

**改动文件：** `ServiceList.tsx`

**交互：** 点击"日志"按钮 → 右侧滑出 Ant Design `Drawer`（宽度 70% 屏幕），内部包含：

```
┌─ Drawer ─────────────────────────────────────┐
│  {serviceName} 日志              [全屏] [关闭] │
├──────────────────────────────────────────────┤
│  类型: [部署日志] [实时跟踪]                    │
├──────────────┬───────────────────────────────┤
│  日志文件列表 │  日志内容区                     │
│  · 2026-06-02│  [10:23:15] Building...       │
│  · 2026-06-01│  [10:23:18] Step 1/8...      │
│              │                               │
│              │  [工具栏: 加载 | 跟踪 | 停止]   │
└──────────────┴───────────────────────────────┘
```

- 打开时自动加载最新部署日志文件
- "全屏"按钮跳转到独立日志页面（`/logs`），预选当前项目和服务
- 重新使用现有的 `LogDrawer` 组件（新建），与日志页面共享日志内容区组件

### 3.4 前端 API 层改动

**`api/logs.ts` 重构：**

```typescript
// 列出日志文件
getLogFiles(serviceId: number, type: 'deploy' | 'app', date?: string): Promise<ApiResponse<string[]>>

// 获取有日志的日期列表
getLogDates(serviceId: number, type: 'deploy' | 'app'): Promise<ApiResponse<string[]>>

// 读取日志内容
getLogContent(serviceId: number, type: string, filename: string, offset?: number, limit?: number): Promise<ApiResponse<string>>

// 获取容器运行日志（保持）
getContainerLogs(serviceId: number, tail?: number): Promise<ApiResponse<string>>
```

**`api/projects.ts` 增加：**

```typescript
// 获取项目列表（含统计信息）
getProjectList(): Promise<ApiResponse<ProjectWithStats[]>>
```

### 3.5 类型定义

**`types/index.ts` 增加：**

```typescript
export interface ProjectWithStats extends Project {
  serviceCount: number
  runningCount: number
  memberCount: number
}

export interface LogFileInfo {
  filename: string
  size: number
  lastModified: string
}
```

---

## 四、安全加固

| 问题 | 修复 |
|------|------|
| `LogController` 无权限校验 | 所有端点加 `@RequirePermission("VIEW")` |
| `LogWebSocketHandler` 无认证 | WebSocket 握手时校验 Sa-Token（从 cookie 或 header 读取） |
| `filename` 参数无路径遍历防护 | 校验 `filename` 不包含 `..`、`/`、`\`，且解析后的路径必须在 `logBasePath` 内 |
| `logBasePath` 硬编码三处 | 统一从 `@Value("${app.logs.path}")` 注入 |
| `ContainerLogWebSocketHandler` 未用 `DockerUtil` | 改用 `DockerUtil.newProcessBuilder()` |

---

## 五、涉及文件清单

### 后端（flowops-app）

| 文件 | 操作 |
|------|------|
| `service/LogService.java` | 重构 |
| `controller/LogController.java` | 重构 |
| `ws/LogWebSocketHandler.java` | 重构 |
| `ws/ContainerLogWebSocketHandler.java` | 小改 |
| `service/DeployExecutorService.java` | 改动日志写入逻辑 + logPath 赋值 |
| `config/WebSocketConfig.java` | 小改 |
| `entity/DeployRecord.java` | 无改动（logPath 字段已有） |
| `application.yml` | 增加 `app.logs.path` 配置 |

### 前端（flowops-front）

| 文件 | 操作 |
|------|------|
| `pages/DeployLogs.tsx` | 重写 |
| `pages/ContainerLogs.tsx` | 删除（功能合并到 DeployLogs + Drawer） |
| `pages/ProjectList.tsx` | 重写（卡片布局） |
| `pages/ServiceList.tsx` | 增加 Drawer 日志查看 |
| `pages/ServiceEdit.tsx` | 增加"应用日志路径"表单字段（容器内日志目录，如 `/app/logs`），为选填，存入 `serviceConfig.appLogPath` |
| `api/logs.ts` | 重构 |
| `api/projects.ts` | 增加带统计的接口 |
| `types/index.ts` | 增加类型定义 |
| `App.tsx` | 移除 ContainerLogs 路由 |
