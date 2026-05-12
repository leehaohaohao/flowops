# 功能计划：服务配置表单化改造

> 分支：`feat/form-service-config`（基于 `develop` 创建）
> 状态：计划阶段，待讨论后实施

---

## 背景

当前服务编辑页（`service-edit.html`）直接暴露两个裸文本框（Dockerfile + docker-compose.yml），用户需要手写完整 YAML，容易出错（缩进、字段拼写、层级关系）。目标是改为**表单驱动 + YAML 自动生成 + 可预览可微调**的模式，降低使用门槛。

---

## 设计方案

### 1. 表单字段定义

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| 服务名称 | 文本输入 | — | 已有，必填 |
| 基础镜像 | 文本输入 | `openjdk:17-jdk-slim` | Dockerfile `FROM`，其他语言可改为对应镜像 |
| 启动命令 | 文本输入 | `java -jar /app/app.jar` | Dockerfile `ENTRYPOINT`，前端项目可改为 nginx 启动命令 |
| 暴露端口 | 数字输入 | 用户填写 | 宿主机端口，容器内默认 8080，可自定义容器端口 |
| 环境变量 | 动态 key-value 列表 | 空 | 可追加/删除，如 `SPRING_PROFILES_ACTIVE=prod` |
| 挂载卷 | 动态列表 | `/data/flowops/services/{name}:/app` | 默认挂载服务目录到容器 `/app`，可自定义追加 |
| 重启策略 | 下拉选择 | `unless-stopped` | `no` / `always` / `unless-stopped` / `on-failure` |

### 2. 自动生成产物

**Dockerfile（自动生成，用户不直接编辑）：**
```dockerfile
FROM openjdk:17-jdk-slim
COPY app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

**docker-compose.yml（自动生成）：**
```yaml
services:
  app:
    build: .
    ports:
      - "8881:8080"
    volumes:
      - /data/flowops/services/flowops-test:/app
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    restart: unless-stopped
```

> 注：`version` 字段已废弃（Docker Compose V2 会警告），生成时去掉。

### 3. 高级模式（折叠区域）

- 「高级：自定义 Dockerfile」→ CodeMirror 编辑器，预填生成的 Dockerfile
- 「高级：自定义 docker-compose.yml」→ CodeMirror 编辑器，预填生成的 YAML
- 勾选对应「使用自定义」复选框后，表单字段值被忽略，以用户编辑的内容为准
- 未勾选时始终以表单值自动生成，覆盖高级编辑器内容

### 4. YAML 预览

- 编辑页新增「预览配置」按钮
- 点击弹出 Modal，左右分栏展示将要生成的 Dockerfile 和 docker-compose.yml
- 部署前可以先预览再确认

### 5. 表单校验

| 校验项 | 规则 | 提示 |
|--------|------|------|
| 服务名称 | 必填 | 请填写服务名称 |
| 基础镜像 | 必填 | 请填写基础镜像 |
| 暴露端口 | 必填，1~65535 | 请填写有效端口号 |
| 启动命令 | 非必填，空则不生成 ENTRYPOINT | — |
| 环境变量 | key 不能为空 | 环境变量名不能为空 |
| 挂载卷 | 格式 `host:container` | 挂载卷格式应为 宿主机路径:容器路径 |

### 6. 前端页面结构

```
┌─────────────────────────────────────────────┐
│  创建/编辑服务                               │
├─────────────────────────────────────────────┤
│  服务名称  [________________________]       │
│  暴露端口  [____]  容器端口  [8080]          │
│  基础镜像  [openjdk:17-jdk-slim        ]    │
│  启动命令  [java -jar /app/app.jar     ]    │
│  重启策略  [unless-stopped ▾]               │
├─────────────────────────────────────────────┤
│  环境变量                          [+ 添加] │
│    SPRING_PROFILES_ACTIVE  [prod    ] [×]  │
│    MYSQL_HOST              [localhost] [×]  │
├─────────────────────────────────────────────┤
│  挂载卷                            [+ 添加] │
│    /data/flowops/services/xxx:/app     [×]  │
├─────────────────────────────────────────────┤
│  ▶ 高级模式（自定义 Dockerfile / compose）    │
│  ┌─────────────────────────────────────────┐│
│  │ ☐ 使用自定义 Dockerfile                  ││
│  │ [CodeMirror 编辑器]                      ││
│  │ ☐ 使用自定义 docker-compose.yml          ││
│  │ [CodeMirror 编辑器]                      ││
│  └─────────────────────────────────────────┘│
├─────────────────────────────────────────────┤
│  [保存]  [预览配置]  [取消]                  │
└─────────────────────────────────────────────┘
```

### 7. 上传产物区域

保持不变（JAR 上传、dist zip 上传），仅在编辑模式显示。

---

## 数据库影响

`deploy_service` 表结构**不需要改动**。

- `dockerfile` 和 `docker_compose` TEXT 字段继续存储最终内容（无论是自动生成还是用户自定义）
- 前端提交时，根据是否勾选「使用自定义」决定发送表单数据还是高级编辑器内容
- 后端 `ServiceMgmtService.createService/updateService` 逻辑不变

---

## 后端改动

| 文件 | 改动 |
|------|------|
| `DeployExecutorService.java` | `generateDefaultCompose()` 可能移除或保留为内部逻辑；`deploy()` 中 Dockerfile 写入逻辑适配新的存储格式 |
| `ServiceMgmtService.java` | 无需改动，继续接收 `dockerfile` + `dockerCompose` 字符串 |
| `ServiceController.java` | 无需改动 |
| `DeployController.java` | 无需改动 |

> 后端变化极小，核心变化在前端。

---

## 前端改动

| 文件 | 改动 |
|------|------|
| `service-edit.html` | 重写：表单字段 + 动态环境变量/挂载卷列表 + 高级模式折叠区 + 预览 Modal |
| `app.js` | 新增：YAML 生成函数、表单校验函数、环境变量/挂载卷动态列表操作函数 |
| `service-list.html` | 无需改动 |

---

## 实施步骤

1. **前端：基础表单** — 替换现有 textareas 为表单字段（镜像、端口、环境变量、挂载卷、重启策略）
2. **前端：YAML 生成** — `app.js` 中实现 `generateDockerfile()` 和 `generateComposeYAML()` 函数
3. **前端：高级模式** — 折叠区域 + CodeMirror 编辑器，勾选「使用自定义」切换数据来源
4. **前端：预览功能** — Modal 展示生成的 Dockerfile + docker-compose.yml
5. **前端：表单校验** — 保存前校验必填字段和格式
6. **后端：适配** — 确认 `deploy()` 逻辑兼容新旧数据格式
7. **测试** — 创建服务、编辑服务、预览、部署全流程验证

---

## 向后兼容

- 已有服务如果 `dockerCompose` 字段已有内容，打开编辑页时自动识别为「高级模式」并回填到 CodeMirror
- 表单字段根据已有 compose 内容尝试反向解析填入（尽力而为，解析失败则进入高级模式）

---

## 待讨论

- [ ] 容器端口是否需要可配置（当前固定 8080）？
- [ ] 挂载卷默认路径是否需要支持 Windows 风格路径？
- [ ] 反向解析已有 compose 内容的优先级（高 or 低）？
- [ ] 是否需要「服务类型」选择（Java / 前端 / 自定义镜像），不同类型的表单字段不同？
