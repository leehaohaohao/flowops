# 设计文档：前后端一体服务部署

> 分支：`feat/form-service-config`
> 日期：2026-05-10

---

## 背景

当前 FlowOps 的一个服务只能部署单一类型的项目（纯后端 JAR 或纯前端 dist）。实际业务中，一个应用通常包含前后端两部分，用户需要创建两个独立服务分别管理，操作繁琐且无法统一生命周期。

**目标**：支持三种服务类型（纯后端、纯前端、前后端一体），一个服务统一管控前后端的部署、启停、重启。

---

## 数据模型

### 数据库变更

`deploy_service` 表新增两个字段，废弃两个旧字段：

| 操作 | 字段 | 类型 | 说明 |
|------|------|------|------|
| 新增 | `service_type` | VARCHAR(20) NOT NULL DEFAULT 'backend' | 服务类型：`backend` / `frontend` / `fullstack` |
| 新增 | `service_config` | TEXT (JSON) | 结构化配置，存储表单数据 |
| 保留（废弃） | `dockerfile` | TEXT | 不再使用，可后续清理 |
| 保留（废弃） | `docker_compose` | TEXT | 不再使用，可后续清理 |
| 保留 | `port` | INT | 暴露到宿主机的端口（前端/纯后端对外端口） |

### serviceConfig JSON 结构

```json
{
  "backend": {
    "baseImage": "openjdk:17-jdk-slim",
    "containerPort": 8090,
    "startupCommand": "java -jar /app/app.jar",
    "envVars": {
      "SPRING_PROFILES_ACTIVE": "prod"
    }
  },
  "frontend": {
    "baseImage": "nginx:alpine",
    "backendUrl": "http://121.40.154.188:8090",
    "customNginxConfig": null
  }
}
```

- `backend` 对象：纯后端和前后端一体时必填，纯前端时忽略
- `frontend` 对象：纯前端和前后端一体时必填，纯后端时忽略
- `envVars`：key-value 键值对，注入到 docker-compose 的 `environment` 段
- `customNginxConfig`：为 `null` 时使用自动生成的 Nginx 配置，有值时使用用户自定义内容
- `backendUrl`：仅纯前端模式使用，填写后端 API 地址（如 `http://121.40.154.188:8090`），Nginx 代理目标。前后端一体模式自动使用 Docker 内部服务名 `http://backend:{containerPort}`
- `startupCommand`：存储为字符串，生成 Dockerfile 时按空格拆分为 JSON 数组作为 ENTRYPOINT 参数（如 `"java -jar /app/app.jar"` → `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`）

---

## 服务类型与部署策略

### 三种服务类型

| 类型 | serviceType | 需要 JAR | 需要 dist | 容器数 |
|------|-------------|----------|-----------|--------|
| 纯后端 | `backend` | 是 | 否 | 1 |
| 纯前端 | `frontend` | 否 | 是 | 1 |
| 前后端一体 | `fullstack` | 是 | 是 | 2 |

### 部署文件生成策略

部署时，引擎在 `volumeDir` 下生成以下文件：

```
volumeDir/
├── app.jar                 # 用户上传的 JAR（不变）
├── dist/                   # 用户上传的前端 dist（不变）
├── Dockerfile              # 后端 Dockerfile（后端/一体模式生成）
├── default.conf            # Nginx 配置（前端/一体模式生成）
└── docker-compose.yml      # 每次部署时重新生成
```

### 纯后端模式

**Dockerfile**：
```dockerfile
FROM {backend.baseImage}
WORKDIR /app
COPY app.jar /app/app.jar
EXPOSE {backend.containerPort}
ENTRYPOINT {backend.startupCommand}
```
如有 `envVars`，在 ENTRYPOINT 前添加 `ENV key=value` 行。

**docker-compose.yml**：
```yaml
services:
  backend:
    build: .
    ports:
      - "{port}:{backend.containerPort}"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    restart: unless-stopped
```

### 纯前端模式

**不生成 Dockerfile**，直接使用 `nginx:alpine` 镜像。

**default.conf**（自动生成）：
```nginx
server {
    listen 80;
    server_name localhost;
    root /usr/share/nginx/html;
    index index.html;

    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    location /api/ {
        proxy_pass http://{后端地址}/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /api/sse/ {
        proxy_pass http://{后端地址}/api/sse/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

> 纯前端模式需要用户额外填写后端地址（如 `http://121.40.154.188:8090`），填入 serviceConfig 的 `frontend.backendUrl` 字段。

**docker-compose.yml**：
```yaml
services:
  frontend:
    image: nginx:alpine
    ports:
      - "{port}:80"
    volumes:
      - ./dist:/usr/share/nginx/html
      - ./default.conf:/etc/nginx/conf.d/default.conf
    restart: unless-stopped
```

### 前后端一体模式

**Dockerfile**（后端）：同纯后端模式。

**default.conf**：同纯前端模式，但 `proxy_pass` 使用 Docker 内部服务名 `http://backend:{backend.containerPort}`。

**docker-compose.yml**：
```yaml
services:
  backend:
    build: .
    expose:
      - "{backend.containerPort}"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    restart: unless-stopped
  frontend:
    image: nginx:alpine
    ports:
      - "{port}:80"
    volumes:
      - ./dist:/usr/share/nginx/html
      - ./default.conf:/etc/nginx/conf.d/default.conf
    depends_on:
      - backend
    restart: unless-stopped
```

**网络要点**：
- 后端使用 `expose`（不映射到宿主机），只在 Docker 内部网络可访问
- 前端通过 `http://backend:{containerPort}` 代理到后端（Docker 内部 DNS 解析）
- 只有前端端口对外暴露，公网只访问前端端口

---

## 前端页面设计

### 表单布局

创建和编辑时，表单根据服务类型动态显示/隐藏对应区域：

```
┌─ 创建/编辑服务 ──────────────────────────────┐
│                                               │
│  服务类型  [前后端一体 ▾]                     │
│  服务名称  [volleyball-community        ]     │
│  暴露端口  [8091]                             │
│                                               │
│  ── 后端配置 ──────────────────────           │
│  （服务类型为"纯前端"时隐藏）                  │
│  基础镜像  [openjdk:17-jdk-slim        ]      │
│  容器端口  [8090]                             │
│  启动命令  [java -jar /app/app.jar     ]      │
│  环境变量                         [+ 添加]    │
│    SPRING_PROFILES_ACTIVE  [prod    ] [×]    │
│                                               │
│  ── 前端配置 ──────────────────────           │
│  （服务类型为"纯后端"时隐藏）                  │
│  基础镜像  [nginx:alpine               ]      │
│  后端地址  [http://121.40.154.188:8090]       │
│  （仅纯前端模式显示，一体模式自动生成）        │
│  ▶ 高级：自定义 Nginx 配置                    │
│    ☐ 使用自定义配置                           │
│    [CodeMirror 编辑器，预填自动生成内容]       │
│                                               │
│  [保存]  [预览配置]  [取消]                   │
├───────────────────────────────────────────────┤
│  上传产物（仅编辑模式显示）                    │
│  ┌─────────────┐  ┌─────────────┐            │
│  │ 上传 JAR    │  │ 上传 dist   │            │
│  │ 后端/一体时  │  │ 前端/一体时  │            │
│  │ 显示        │  │ 显示        │            │
│  └─────────────┘  └─────────────┘            │
└───────────────────────────────────────────────┘
```

### 交互行为

- 切换服务类型时：对应的配置区域显示/隐藏，表单值保留不丢失
- 预览配置：弹出 Modal 展示将要生成的 Dockerfile（如有）+ default.conf（如有）+ docker-compose.yml
- 编辑模式：可更改服务类型，保存后下次部署使用新类型

---

## 后端改动清单

| 文件 | 改动内容 |
|------|----------|
| `src/main/resources/sql/init.sql` | 新增 `service_type` 和 `service_config` 字段；移除 `dockerfile` 和 `docker_compose`（或保留但不使用） |
| `entity/DeployService.java` | 新增 `serviceType`、`serviceConfig` 属性 |
| `service/ServiceMgmtService.java` | `createService`/`updateService` 处理 `serviceType` 和 `serviceConfig` 字段 |
| `service/DeployExecutorService.java` | 核心改造：`deploy()` 方法实现新的文件生成逻辑；移除 `generateDefaultCompose()`；新增 `generateDockerfile()`、`generateComposeYml()`、`generateNginxConf()` 方法 |
| `templates/service-edit.html` | 重写表单：服务类型下拉 + 动态区域 + 预览 Modal |
| `static/js/app.js` | 新增服务类型切换逻辑、环境变量列表操作、预览功能 |

---

## 验收标准

1. 创建纯后端服务 → 上传 JAR → 部署 → 单容器运行，端口可访问
2. 创建纯前端服务 → 上传 dist zip → 部署 → Nginx 容器运行，页面可访问
3. 创建前后端一体服务 → 上传 JAR + dist → 部署 → 双容器运行，前端页面可访问，API 通过 Nginx 代理正常工作
4. 编辑服务可切换类型，保存后重新部署生效
5. 预览配置 Modal 正确展示生成的文件内容
6. 停止/重启/删除对所有容器生效（compose 级别操作）
