# Fullstack 模式端口映射修正计划

## Context

用户有一个 courseware 项目，包含后端和前端，希望通过 FlowOps 的 fullstack 模式部署。当前存在以下问题：

1. **前端预览中 extraPorts 归属错误** — fullstack 模式下，前端预览把额外端口加到了 frontend 容器，而后端实际生成是加到 backend 容器
2. **前端容器端口硬编码为 80** — nginx 容器内部端口固定为 80，不支持自定义
3. **nginx 监听端口硬编码为 80** — `listen 80` 写死，不支持自定义
4. **fullstack 模式前端没有独立的暴露端口** — 前端只能用主端口，无法单独配置

### 用户的 courseware 场景

```
后端: 10001(HTTP) + 10002(WebSocket)
前端: 独立端口（如 10000）
nginx 监听: 可自定义（如 80 或 10001）
```

---

## 修改方案

### 一、后端 `DeployExecutorService.java`

**文件**: `flowops-app/src/main/java/com/nexa/flowops/service/DeployExecutorService.java`

#### 1. `generateNginxConf` 方法 (第 293-354 行)

- 新增参数 `int nginxListenPort`
- 将硬编码的 `listen 80` 改为 `listen {nginxListenPort}`
- 从 `frontendConfig` 读取 `nginxListenPort`，默认 80

#### 2. `generateComposeYml` 方法 (第 356-414 行)

**fullstack 模式** (第 385-409 行)：
- 从 `frontendConfig` 读取 `containerPort`（nginx 容器内部端口），默认 80
- 从 `frontendConfig` 读取 `frontendPort`（前端宿主机暴露端口），默认使用 `hostPort`
- 生成 `"{frontendPort}:{containerPort}"` 映射
- extraPorts 保持在 backend 容器（当前逻辑正确）

**frontend 模式** (第 375-384 行)：
- 同样从 `frontendConfig` 读取 `containerPort`，默认 80
- 将 `:80` 改为 `:{nginxContainerPort}`

#### 3. `deploy` 方法 (第 165-178 行)

- 读取 `nginxListenPort` 参数传给 `generateNginxConf`

---

### 二、前端 `ServiceEdit.tsx`

**文件**: `D:\project\front\flowops-front\src\pages\ServiceEdit.tsx`

#### 1. `ServiceConfig` 接口 (第 45-59 行)

新增字段：
- `containerPort: number` — nginx 容器内部端口，默认 80
- `frontendPort: number` — 前端宿主机暴露端口（仅 fullstack）
- `nginxListenPort: number` — nginx listen 端口，默认 80

#### 2. `generateNginxConf` 函数 (第 69-100 行)

- 新增参数 `nginxListenPort: number = 80`
- 将 `listen 80` 改为 `listen ${nginxListenPort}`

#### 3. `generatePreview` 函数 (第 102-150 行)

**fullstack 模式** (第 136-146 行)：
- 读取 `frontendPort` 和 `containerPort`（nginx 端口）
- extraPorts 改为加到 backend 容器（当前错误地加到了 frontend）
- nginx 配置使用自定义端口

**frontend 模式**：同理使用 `containerPort` 替代硬编码 80。

#### 4. `collectConfig` 函数 (第 206-239 行)

在 `config.frontend` 中新增字段收集：`containerPort`、`frontendPort`、`nginxListenPort`

#### 5. 表单 UI — 前端配置区域 (第 411-535 行)

新增三个表单项：
- Nginx 容器端口（默认 80）
- 前端暴露端口（仅 fullstack 模式显示）
- Nginx 监听端口（默认 80）

#### 6. 编辑模式回显 (第 185-200 行)

新增字段回显：`frontendContainerPort`、`frontendPort`、`nginxListenPort`

---

### 三、修改文件清单

| 文件 | 改动 |
|------|------|
| `flowops-app/.../DeployExecutorService.java` | `generateNginxConf` 增加端口参数；`generateComposeYml` fullstack/frontend 模式支持自定义端口；`deploy` 方法传递端口参数 |
| `D:\project\front\flowops-front\src\pages\ServiceEdit.tsx` | ServiceConfig 接口新增字段；generateNginxConf/generatePreview 支持自定义端口；表单新增端口配置项；回显逻辑补充 |

### 四、数据库

**无需数据库变更**。新增的端口配置都存储在已有的 `serviceConfig` JSON 字段中，不需要新的列。

---

## 验证方式

1. 创建一个 fullstack 类型服务，配置：
   - 主暴露端口: 10000
   - 后端容器端口: 10001
   - 额外端口: 10002:10002
   - Nginx 容器端口: 80
   - 前端暴露端口: 10000
   - Nginx 监听端口: 80
2. 点击"预览配置"，检查：
   - backend 容器有 `expose: ["10001"]` + `ports: ["10002:10002"]`
   - frontend 容器有 `ports: ["10000:80"]`
   - nginx.conf 中 `listen 80`
3. 保存并部署，确认容器正常启动
