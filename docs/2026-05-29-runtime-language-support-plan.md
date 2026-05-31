# 添加运行时语言支持 + Go 部署适配

## 背景

当前 FlowOps 的 `serviceType` 只区分部署拓扑（backend/frontend/fullstack），没有"编程语言"的概念。Dockerfile 生成和上传逻辑全部硬编码为 Java（`COPY app.jar`、`openjdk:17-jdk-slim`、`java -jar`）。

**需求**：
1. 服务列表展示编程语言（后端语言 + 前端语言分开显示）
2. 语言影响部署策略（本阶段新增 Go 支持）
3. 前端运行时（Vue/React）仅做展示标签，不影响部署
4. 为后续 Node.js、Python 等语言预留扩展点
5. 上传产物支持拖拽上传（同时支持点击选择和拖拽两种方式）

---

## 数据模型设计

### 方案：在 serviceConfig JSON 中增加 `runtime` 字段

**不新增数据库列**，利用已有的 `serviceConfig` JSON 结构，在 `backend` 和 `frontend` 子对象中各加一个 `runtime` 字段：

```json
{
  "backend": {
    "runtime": "go",          // 新增: java | go（后续扩展 node/python）
    "baseImage": "golang:1.26.3-alpine",
    "startupCommand": "/app/app",
    "envVars": {},
    "dataMount": {}
  },
  "frontend": {
    "runtime": "vue",         // 新增: vue | react | static（纯展示，不影响部署）
    "baseImage": "nginx:alpine",
    ...
  }
}
```

**理由**：runtime 本质是部署配置的一部分，影响 baseImage、COPY 路径、启动命令等，放在 serviceConfig 内语义更连贯，且无需数据库 migration。

### 语言预设表（前端硬编码常量）

#### 后端运行时（影响部署）

| runtime | 标签  | 默认 baseImage          | 默认 startupCommand         | 上传产物   |
|---------|-------|------------------------|----------------------------|-----------|
| java    | Java  | openjdk:17-jdk-slim    | java -jar /app/app.jar     | .jar      |
| go      | Go    | golang:1.26.3-alpine   | /app/app                   | 二进制文件  |

#### 前端运行时（仅展示标签）

| runtime  | 标签     |
|----------|---------|
| vue      | Vue     |
| react    | React   |
| static   | 静态页面  |

前端选择 runtime 后自动填充对应的默认值，用户仍可手动覆盖。

---

## 实现步骤

### Step 1: 后端 — Dockerfile 生成适配 runtime

**文件**: `DeployExecutorService.java` — `generateDockerfile()` 方法 (L274-323)

- 从 `backendConfig` 读取 `runtime` 字段，默认 `"java"`（向后兼容）
- 根据 runtime 分支：
  - **java**: 保持现有逻辑不变（`COPY app.jar`, 默认 `openjdk:17-jdk-slim`）
  - **go**: `COPY app /app/app` + `RUN chmod +x /app/app`，默认 `golang:1.26.3-alpine`
- 其余逻辑（EXPOSE、ENV、ENTRYPOINT）不变，仍由用户配置驱动

### Step 2: 后端 — 上传逻辑适配

**文件**: `DeployController.java` — `upload()` 方法 (L22-37)

- 当 `type=jar` 时仍强制命名为 `app.jar`（Java 保持不变）
- 新增 `type=binary`，强制命名为 `app`（Go 二进制产物）
- 前端根据 runtime 自动选择上传 type

**文件**: `DeployExecutorService.java` — `getUploadPath()` 方法

- 新增对 `binary` type 的处理（与 `jar` 同路径，即 volumeDir）

### Step 3: 前端 — 服务编辑页增加 runtime 选择 + 拖拽上传

**文件**: `ServiceEdit.tsx`

#### 3a. Runtime 选择器

- 后端配置区新增 `<Select>` — "运行时"，选项：`Java` / `Go`
- 选择 runtime 后自动填充默认值：
  - `java`: baseImage → `openjdk:17-jdk-slim`, startupCommand → `java -jar /app/app.jar`
  - `go`: baseImage → `golang:1.26.3-alpine`, startupCommand → `/app/app`
- 前端配置区新增运行时选择（vue / react / static），仅做标签用途
- `collectConfig()` 中将 runtime 写入 backend/frontend 子对象
- 编辑模式加载时从 serviceConfig 读取 runtime 并回填

#### 3b. 拖拽上传（Drag & Drop）

将现有的上传区域从纯点击按钮改为 **Ant Design Upload.Dragger**，同时支持点击选择和拖拽上传：

**后端产物上传区**（根据 runtime 动态显示）：
- Java → "拖拽或点击上传 JAR 文件"，accept=`.jar`
- Go → "拖拽或点击上传二进制文件"，accept 无限制（或 `.exe,.bin`）

**前端产物上传区**（serviceType 为 frontend/fullstack 时显示）：
- "拖拽或点击上传前端 dist (zip)"，accept=`.zip`

**UI 交互**：
- 拖拽区域显示虚线边框 + 图标 + 提示文字（如 "将文件拖到此处，或点击上传"）
- 拖入文件时边框高亮变色（视觉反馈）
- 上传中显示进度条
- 上传成功/失败显示对应提示
- 使用 Ant Design `<Upload.Dragger>` 组件，复用现有 `beforeUpload` 逻辑

**代码改动**：
- 将现有 `<Upload beforeUpload={...}>` 替换为 `<Upload.Dragger>`
- 保持原有的 `handleUploadJar` / `handleUploadDist` 逻辑不变
- Go 二进制上传复用 `uploadJar` 接口，type 参数改为 `binary`

### Step 4: 前端 — 服务列表展示语言标签

**文件**: `ServiceList.tsx`

在"类型"列后新增"语言"列：

```
| 类型        | 语言              |
|------------|-------------------|
| 纯后端      | [Java]            |
| 前后端一体   | [Java] [Vue]      |
| 纯前端      | [React]           |
```

- 解析 `serviceConfig` JSON 提取 `backend.runtime` 和 `frontend.runtime`
- 用 `<Tag>` 彩色标签展示，不同语言用不同颜色
- 语言标签颜色映射：Java=orange, Go=cyan, Vue=green, React=blue, static=default

### Step 5: 前端 — 预览功能适配

**文件**: `ServiceEdit.tsx` — `generatePreview()` 函数

- Dockerfile 预览根据 runtime 生成不同内容（与后端逻辑一致）
- 确保预览和实际部署行为一致

---

## 关键文件清单

| 文件 | 改动类型 |
|------|---------|
| `flowops-app/.../service/DeployExecutorService.java` | Dockerfile 生成分支 + upload path |
| `flowops-app/.../controller/DeployController.java` | 上传 type=binary 支持 |
| `flowops-front/src/pages/ServiceEdit.tsx` | runtime 选择器 + 默认值联动 + 上传 |
| `flowops-front/src/pages/ServiceList.tsx` | 语言标签列 |
| `flowops-front/src/pages/ServiceEdit.tsx` | preview 函数适配 |

---

## 向后兼容

- 现有服务的 serviceConfig 没有 `runtime` 字段 → 后端默认按 `"java"` 处理，前端默认选中 Java
- **不需要数据库 migration**
- 不修改 serviceType 的任何逻辑

## 验证方式

1. 创建一个 Go 类型后端服务，验证 Dockerfile 生成为 `COPY app /app/app` + `RUN chmod +x /app/app`
2. 上传 Go 二进制文件，验证保存为 `app` 而非 `app.jar`
3. 服务列表验证语言标签正确显示
4. 编辑现有 Java 服务，验证 runtime 默认回填为 Java，行为不变
5. fullstack 服务验证后端语言 + 前端语言同时展示
6. 验证拖拽上传：将文件拖入上传区域，确认边框高亮、文件正常上传
7. 验证点击上传：点击上传区域选择文件，确认行为与现有逻辑一致
8. 验证不同 runtime 下上传区域文案和 accept 类型正确切换
