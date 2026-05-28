# Plan: 为 DeployService 增加 deployName + remark 字段

## Context

Docker Compose 的 project name 要求符合 hostname 规范（RFC 952/1123），只允许小写字母、数字和连字符。CentOS 上中文目录名虽然文件系统支持，但 Docker Compose 会拒绝。

目前 `DeployService.name` 同时用于 UI 展示和以下所有 Docker 相关操作：
- **volumeDir 目录名**（`ServiceMgmtService:51`）：`/data/flowops/services/{name}`
- **上传文件目录**（`DeployExecutorService:56`）：jar/dist 文件上传到 `volumeDir` 下
- **Docker Compose project name**：隐式从 `volumeDir` 目录名派生
- **容器状态检查**（`DockerUtil:46`）：`docker ps --filter name={name}`

中文名会导致 Docker Compose 直接失败，需要将"显示名"和"部署名"解耦。同时增加 `remark` 备注字段，方便记录服务用途说明。

**范围：仅 DeployService，Project 不改动。**

---

## 改动清单

### 一、后端数据库

**1. `flowops-app/src/main/resources/sql/migration.sql`**

新增 ALTER TABLE 迁移：
```sql
ALTER TABLE deploy_service ADD COLUMN deploy_name VARCHAR(63) NOT NULL AFTER name;
ALTER TABLE deploy_service ADD UNIQUE KEY uk_deploy_name (deploy_name);
ALTER TABLE deploy_service ADD COLUMN remark VARCHAR(500) DEFAULT NULL COMMENT '服务备注' AFTER deploy_name;
```

更新 CREATE TABLE 语句，加入 `deploy_name` 列（UNIQUE 索引）和 `remark` 列。

更新已有 seed INSERT，补充 `deploy_name` 值（如 `'default-service'`）。

### 二、后端 Entity + DTO

**2. `flowops-app/.../entity/DeployService.java`**
- 新增 `private String deployName;`
- 新增 `private String remark;`

**3. `flowops-app/.../dto/CreateServiceRequest.java`**
- 新增 `private String deployName;`
- 新增 `private String remark;`

**4. `flowops-app/.../dto/UpdateServiceRequest.java`**
- 新增 `private String deployName;`
- 新增 `private String remark;`

（Entity 直接序列化返回，getService/getServiceList 自动包含新字段，无需改 VO）

### 三、后端 Service 层

**5. `flowops-app/.../service/ServiceMgmtService.java`**

`createService()` 方法改动：
- 校验 `deployName` 非空
- 格式校验：`^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$`，不合规抛 BusinessException
- 唯一性校验改为检查 `deployName`（`name` 可以中文、允许重复）
- `volumeDir` 改为 `storagePath + "/" + req.getDeployName()`
- 物理目录创建使用新 `volumeDir`
- 设置 `remark` 字段

`updateService()` 方法改动：
- 支持更新 `deployName` 字段（含格式校验 + 唯一性校验）
- `deployName` 变更时，同步更新 `volumeDir` 并 rename 物理目录
- 支持更新 `remark` 字段

**影响范围（volumeDir 变更后自动生效）：**
- 上传 jar/dist 文件路径 → `DeployExecutorService.getUploadPath()` 使用 `volumeDir`
- Docker Compose 工作目录 → 所有 `pb.directory(new File(volumeDir))` 调用
- 生成 docker-compose.yml / Dockerfile / nginx.conf 位置 → `generateComposeYml` 等方法

### 四、后端 DeployExecutorService（无需改动）

当前通过 `pb.directory(volumeDir)` 隐式确定 project name，`volumeDir` 已改为基于 `deployName`，Docker Compose 会自动从目录名派生 project name，无需额外改动。

### 五、前端类型

**6. `flowops-front/src/types/index.ts`**

`DeployService` 接口新增：
```typescript
deployName: string
remark?: string
```

### 六、前端页面

**7. `flowops-front/src/pages/ServiceEdit.tsx`**

- 在"服务名称"表单字段下方新增"部署名称"输入框
- 字段名：`deployName`，label：`部署名称`
- 校验规则：
  - `required: true`，message: `请输入部署名称`
  - `pattern: /^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$/`，message: `只能包含小写字母、数字和连字符，且首尾为字母或数字`
  - `max: 63`
- tooltip：`用于 Docker Compose 项目命名和文件目录，只能包含小写字母、数字和连字符`
- 编辑模式：回填已有 `deployName` 值
- `handleSave` 的 payload 中加入 `deployName`

新增"备注"输入框（在 deployName 下方）：
- 字段名：`remark`，label：`备注`
- `<Input.TextArea rows={2}>`，可选填
- placeholder：`可选，记录服务用途说明`
- `handleSave` 的 payload 中加入 `remark`

**8. `flowops-front/src/pages/ServiceList.tsx`**

- 表格在"服务名"列后新增"部署名称"列：
  ```typescript
  { title: '部署名称', dataIndex: 'deployName', width: 150 }
  ```
- 表格新增"备注"列：
  ```typescript
  { title: '备注', dataIndex: 'remark', ellipsis: true }
  ```

---

## 执行顺序

1. `migration.sql`（ALTER TABLE + CREATE TABLE + seed）
2. `DeployService.java`（entity）
3. `CreateServiceRequest.java` + `UpdateServiceRequest.java`（DTO）
4. `ServiceMgmtService.java`（校验 + volumeDir 逻辑）
5. `types/index.ts`（前端类型）
6. `ServiceEdit.tsx`（表单）
7. `ServiceList.tsx`（列表）

## 验证方式

1. 创建服务：中文名（如"我的后端"）+ 英文 deployName（如 `my-backend`），确认 volumeDir = `/data/flowops/services/my-backend`
2. 上传 jar 文件，确认存储在 `/data/flowops/services/my-backend/app.jar`
3. `docker compose -f /data/flowops/services/my-backend/docker-compose.yml up -d --build` 正常执行
4. 容器名基于 deployName 生成（如 `my-backend-backend-1`）
5. 前端校验：deployName 不允许中文、大写、特殊字符
6. deployName 唯一性校验生效（重复报错）
7. 编辑时修改 deployName，volumeDir 和物理目录同步更新
