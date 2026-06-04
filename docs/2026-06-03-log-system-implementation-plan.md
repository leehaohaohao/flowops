# 日志系统重构 + 项目列表优化 实施计划

> 日期：2026-06-03
> 基于设计方案：`2026-06-02-log-system-redesign.md`

## Context

当前日志系统存在多个问题：扁平目录存储、无项目/服务维度区分、权限缺失、路径硬编码、WebSocket 无认证。项目列表缺少运行中服务数统计，且布局不够直观。本计划按照设计文档分阶段实施。

---

## Phase 4: 前端 - 类型与 API 层

### Step 4.1: types/index.ts 增加类型

**文件**: `flowops-front/src/types/index.ts`

- `Project` 接口增加 `runningCount?: number`
- 新增 `LogFileInfo` 接口（备用）

### Step 4.2: 重构 api/logs.ts

**文件**: `flowops-front/src/api/logs.ts`

```typescript
getLogFiles(serviceId, type, date)                    // GET /api/logs/list
getLogDates(serviceId, type)                          // GET /api/logs/dates
getLogContent(serviceId, type, date, filename, offset?, limit?)  // GET /api/logs/content
getContainerLogs(serviceId, tail?)                    // GET /api/deploy/logs/:id (保持)
```

### Step 4.3: api/projects.ts 无需改动

后端 `ProjectDetailVO` 新增 `runningCount` 字段，现有 `getProjectList()` 自动获取。

---

## Phase 5: 前端 - DeployLogs 页面重写

### Step 5.1: 重写 DeployLogs.tsx

**文件**: `flowops-front/src/pages/DeployLogs.tsx`

全新布局：
- 筛选栏：项目下拉 → 服务下拉 → 类型 Tab（部署日志/应用日志）→ 日期选择
- 左右分栏：日志文件列表 | 日志内容区
- 工具栏：实时跟踪按钮、下载、清空
- 状态栏：连接状态
- WebSocket 传 JSON `{serviceId, type, filename}`，token 通过 URL query 参数传递

### Step 5.2: 创建 LogViewer 组件

**新文件**: `flowops-front/src/components/LogViewer.tsx`

共享的日志内容展示组件（深色背景 `<pre>` + 自动滚动），供 DeployLogs 和 LogDrawer 复用。

### Step 5.3: 创建 LogDrawer 组件

**新文件**: `flowops-front/src/components/LogDrawer.tsx`

Ant Design Drawer（70% 宽度），包含：
- 类型选择：部署日志 Tab + 实时跟踪 Tab
- 部署日志：文件列表 + LogViewer
- 实时跟踪：连接 /ws/container-logs
- "全屏"按钮跳转到 `/logs`

---

## Phase 6: 前端 - ServiceList Drawer + ServiceEdit

### Step 6.1: 修改 ServiceList.tsx

**文件**: `flowops-front/src/pages/ServiceList.tsx`

- "日志"按钮改为打开 LogDrawer（替代跳转到 ContainerLogs 页面）

### Step 6.2: 修改 ServiceEdit.tsx

**文件**: `flowops-front/src/pages/ServiceEdit.tsx`

- 后端配置区增加"应用日志路径（容器内）"输入框
- 存入 `serviceConfig.appLogPath`
- 更新 `ServiceConfig` 接口和 `collectConfig()`/表单加载逻辑

---

## 风险与注意事项

1. **WebSocket 认证**: 前端需在 WebSocket URL 中传递 token（`?token=xxx`），后端需在握手时验证
2. **路径遍历防护**: 使用 `Path.resolve().normalize()` + 前缀校验，不能仅靠字符串检查 `..`
3. **日期目录创建**: 日志写入前必须 `mkdirs()` 创建日期目录，读取时若目录不存在应返回空列表
4. **部署日志追加模式**: 从覆写改为 APPEND，需正确实现文件大小拆分逻辑
5. **ContainerLogs 路由删除**: 已有书签会失效，建议添加路由重定向
6. **appLogPath 仅在下次部署生效**: 已运行服务需重新部署才生效

## 验证方式

1. 后端编译通过：`mvn compile`
2. 前端编译通过：`npm run build`
3. 手动测试：部署一个服务 → 验证日志写入新路径 `/data/flowops/logs/{projectId}/{serviceId}/deploy/{date}/HH-mm-ss.log` → 在 DeployLogs 页面按项目/服务/日期筛选查看
4. 权限测试：无 VIEW 权限的用户访问日志 API 应返回 403
5. 安全测试：尝试路径遍历 filename（如 `../../etc/passwd`）应被拒绝
