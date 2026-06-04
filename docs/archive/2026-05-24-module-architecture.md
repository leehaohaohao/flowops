# FlowOps 多模块架构详解

## 1. 模块总览

FlowOps 从单体应用重构为三模块 Maven 项目，实现了权限系统与业务逻辑的解耦。

```
flowops/                        父 POM (packaging=pom)
├── flowops-common/             共享工具（纯 Java，无框架依赖）
├── flowops-permission/         权限模块（独立 JAR 库）
└── flowops-app/                主应用（Spring Boot 可执行 JAR）
```

### 依赖关系

```
flowops-common  (Result, BusinessException, 注解)
      ^                ^
      |                |
flowops-permission  (权限引擎, Sa-Token, MyBatis-Plus)
      ^
      |
flowops-app  (业务逻辑, Docker, WebSocket, Thymeleaf)
```

**核心原则：** 依赖只能向下流动，不能反向或循环。

---

## 2. 各模块详细结构

### 2.1 flowops-common — 共享工具

**包名：** `com.nexa.flowops.common`
**依赖：** 仅 Lombok (`provided` scope)
**定位：** 被权限模块和主应用共同依赖的纯 Java 工具类

| 文件 | 用途 |
|------|------|
| `Result.java` | 统一 API 响应封装 `{code, msg, data}` |
| `BusinessException.java` | 业务异常类 |
| `RequirePermission.java` | 权限校验注解（如 `@RequirePermission("DEPLOY")`） |
| `RequireGroupSupervisor.java` | 组管理员校验注解 |

**为什么需要这个模块？** `Result.java` 和 `BusinessException.java` 被权限模块的 Controller 和主应用的 Service 共同使用。如果放在权限模块，主应用要为通用工具类依赖权限模块（概念倒置）；如果复制到两个模块，同名类会导致类型不兼容。

### 2.2 flowops-permission — 权限模块

**包名：** `com.nexa.flowops.permission`
**依赖：** flowops-common, Spring Web, Spring AOP, Sa-Token, MyBatis-Plus, MySQL Connector
**定位：** 独立的权限库 JAR，不含 `spring-boot-maven-plugin`（不可直接运行）

#### 文件清单（40 个 Java 文件 + 3 个资源文件）

**顶层接口**
- `ExternalDataProvider.java` — 跨模块数据查询契约（详见第 3 节）

**entity/ — 数据实体（8 个）**

| 实体 | 对应表 | 说明 |
|------|--------|------|
| `SysUser` | `sys_user` | 用户，含 `isSuperAdmin` 标志 |
| `ProjectGroup` | `project_group` | 项目组（组织单元） |
| `Project` | `project` | 项目，属于某个项目组 |
| `GroupMember` | `group_member` | 组成员关联，含角色和额外权限 |
| `PermDefinition` | `perm_definition` | 权限码定义（如 VIEW, DEPLOY） |
| `PermRole` | `perm_role` | 角色定义（viewer, operator, admin 等） |
| `RolePermission` | `role_permission` | 角色-权限映射 |
| `ProjectAccess` | `project_access` | 跨组项目授权 |

**mapper/ — MyBatis-Plus 数据访问（8 个）**

每个实体对应一个 Mapper 接口，继承 `BaseMapper<T>`。`SysUserMapper` 额外有 XML 映射文件（`resources/mapper/SysUserMapper.xml`）用于自定义查询。

**dto/ — 请求 DTO（9 个）**

`CreateUserRequest`, `AddMemberRequest`, `UpdateMemberRoleRequest`, `CreateRoleRequest`, `UpdateRoleRequest`, `GrantAccessRequest`, `GroupRequest`, `CreateProjectRequest`, `UpdateProjectRequest`

**service/ — 业务逻辑（6 个）**

| Service | 职责 |
|---------|------|
| `PermissionService` | 核心权限引擎：解析有效权限、检查权限、获取可见项目 |
| `UserService` | 用户 CRUD |
| `RoleService` | 角色 CRUD + 权限分配 |
| `MemberService` | 组成员管理（添加/移除/改角色） |
| `ProjectGroupService` | 项目组 CRUD |
| `ProjectService` | 项目 CRUD，删除前检查是否有关联服务 |

**config/ — 配置与切面（4 个）**

| 配置类 | 职责 |
|--------|------|
| `PermissionAspect` | AOP 切面，拦截 `@RequirePermission` 和 `@RequireGroupSupervisor` 注解 |
| `PermissionInterceptor` | URL 模式匹配拦截器（Sa-Token 路由级权限） |
| `StpInterfaceImpl` | Sa-Token 的 `StpInterface` 实现，提供权限码列表 |
| `SaTokenConfig` | Sa-Token 全局配置（拦截器注册、排除路径） |

**controller/ — REST API（6 个）**

`PermissionController`, `RoleController`, `MemberController`, `ProjectGroupController`, `ProjectController`, `UserController`

**resources/**

- `mapper/SysUserMapper.xml` — MyBatis XML 映射
- `sql/permission-schema.sql` — 建表 DDL
- `sql/permission-data.sql` — 种子数据（权限码、预设角色）

### 2.3 flowops-app — 主应用

**包名：** `com.nexa.flowops`
**依赖：** flowops-common, flowops-permission, WebSocket, Thymeleaf, dotenv-java
**定位：** Spring Boot 可执行 JAR，包含 `FlowopsApplication.java` 启动类

#### 文件清单（28 个 Java 文件）

**resolver/ — ExternalDataProvider 实现（1 个）**
- `FlowOpsExternalDataProvider.java` — 实现权限模块定义的 `ExternalDataProvider` 接口

**entity/ — 业务实体（2 个）**
- `DeployService.java` — 部署服务
- `DeployRecord.java` — 部署记录

**mapper/ — 业务 Mapper（2 个）**
- `DeployServiceMapper.java`
- `DeployRecordMapper.java`

**service/ — 业务逻辑（5 个）**
- `AuthService` — 登录认证
- `DashboardService` — 仪表盘统计
- `ServiceMgmtService` — 服务管理
- `DeployExecutorService` — 部署执行引擎（核心）
- `LogService` — 日志查询

**controller/ — REST API（5 个）**
- `AuthController`, `ServiceController`, `DeployController`, `LogController`, `StatsController`

**config/ — 应用配置（6 个）**
- `AutoFillHandler`, `CorsFilter`, `DotenvPostProcessor`, `GlobalExceptionHandler`, `TraceFilter`, `WebSocketConfig`

**其他**
- `util/DockerUtil.java` — Docker CLI 工具
- `ws/ContainerLogWebSocketHandler.java`, `ws/LogWebSocketHandler.java` — WebSocket 处理器

---

## 3. ExternalDataProvider 设计模式详解

### 3.1 问题：循环依赖

权限模块需要知道"某个服务属于哪个项目"才能做权限校验。在重构前的单体应用中，`PermissionService` 直接注入了 `DeployServiceMapper`：

```
# 重构前（单体）
PermissionService → DeployServiceMapper → DeployService（业务实体）
```

抽取模块后，这变成了循环依赖：

```
flowops-permission 需要 DeployServiceMapper（属于 flowops-app）
flowops-app 需要 PermissionService（属于 flowops-permission）

→ A 依赖 B，B 依赖 A = 编译失败
```

### 3.2 解决方案：依赖倒置原则

**核心思想：** "依赖抽象，不依赖具体实现。"

权限模块定义接口，主应用提供实现。这样依赖方向变为单向：

```
flowops-permission 定义接口 ExternalDataProvider
        ↑
        | （实现）
flowops-app 提供 FlowOpsExternalDataProvider
```

### 3.3 接口定义（flowops-permission）

```java
package com.nexa.flowops.permission;

/**
 * 跨模块数据查询接口。
 * 权限模块通过此接口获取业务数据，避免直接依赖业务实体。
 * 集成方需提供此接口的 @Component 实现。
 */
public interface ExternalDataProvider {

    /** 根据服务 ID 查询所属项目 ID */
    Long getProjectIdByServiceId(Long serviceId);

    /** 统计项目下的服务数量 */
    long countServicesByProjectId(Long projectId);

    /**
     * 扩展点：新增查询方法应声明为 default 方法，
     * 返回 null/空值作为默认值，避免破坏现有实现。
     */
}
```

### 3.4 实现（flowops-app）

```java
package com.nexa.flowops.resolver;

@Component
public class FlowOpsExternalDataProvider implements ExternalDataProvider {

    private final DeployServiceMapper deployServiceMapper;

    public FlowOpsExternalDataProvider(DeployServiceMapper deployServiceMapper) {
        this.deployServiceMapper = deployServiceMapper;
    }

    @Override
    public Long getProjectIdByServiceId(Long serviceId) {
        DeployService service = deployServiceMapper.selectById(serviceId);
        return service != null ? service.getProjectId() : null;
    }

    @Override
    public long countServicesByProjectId(Long projectId) {
        return deployServiceMapper.selectCount(
            new LambdaQueryWrapper<DeployService>()
                .eq(DeployService::getProjectId, projectId));
    }
}
```

### 3.5 调用链路

以"检查用户是否有权部署某个服务"为例：

```
1. 用户请求 POST /api/deploy/start/42
2. DeployController 方法上有 @RequirePermission("DEPLOY")
3. PermissionAspect 拦截，调用 permissionService.checkServicePermission(userId, 42, "DEPLOY")
4. PermissionService 需要知道服务 42 属于哪个项目
5. 调用 externalDataProvider.getProjectIdByServiceId(42)
6. FlowOpsExternalDataProvider 查询 DeployService 表，返回 projectId = 5
7. PermissionService 查询用户在项目 5 的有效权限
8. 检查是否包含 "DEPLOY" 权限码
9. 通过 → 执行部署；不通过 → 返回 403
```

### 3.6 为什么用接口而不是直接依赖？

| 方案 | 问题 |
|------|------|
| 权限模块直接依赖 `DeployServiceMapper` | 循环依赖，编译失败 |
| 把 `DeployService` 复制到权限模块 | 两份实体，数据不一致 |
| 用 `Object` + 反射 | 类型不安全，运行时才报错 |
| **定义接口，主应用实现** | **单向依赖，类型安全，可测试** |

### 3.7 如何扩展

当权限模块需要新的跨模块查询时：

1. 在 `ExternalDataProvider` 接口中添加 `default` 方法：

```java
default Long getProjectIdByPipelineId(Long pipelineId) {
    return null;  // 默认值，现有实现不会报错
}
```

2. 在 `FlowOpsExternalDataProvider` 中覆盖实现：

```java
@Override
public Long getProjectIdByPipelineId(Long pipelineId) {
    // 查询 Pipeline 表
}
```

使用 `default` 方法保证向后兼容——已有的实现类不需要立即更新。

---

## 4. 权限系统工作原理

### 4.1 数据模型

```
ProjectGroup (项目组)
  └── Project (项目)
        └── DeployService (部署服务)  ← 在 flowops-app 中

SysUser (用户)
  └── GroupMember (组成员)
        ├── PermRole (角色)
        │     └── RolePermission (角色权限码)
        └── extraPermissions (额外权限码，逗号分隔)

ProjectAccess (跨组授权)
  └── user_id + project_id + perm_code
```

### 4.2 权限解析流程（getEffectivePermissions）

```
输入：userId, projectId

1. 超级管理员？
   → 是：返回全部权限码 [VIEW, DEPLOY, START, STOP, UPLOAD, EDIT_CONFIG, DELETE, MANAGE_MEMBERS, MANAGE_PROJECTS]

2. 查找项目所属项目组 → groupId

3. 查找用户在该组的 GroupMember 记录
   → 获取角色的 RolePermission 列表
   → 合并 extraPermissions（逗号分隔字符串）

4. 查找用户的 ProjectAccess 记录（跨组授权）
   → 合并额外的 permCode

5. 返回去重后的权限码集合
```

### 4.3 两种权限校验方式

**方式一：注解 + AOP（推荐）**

```java
@RequirePermission(value = "DEPLOY", project = "params.projectId")
@PostMapping("/deploy")
public Result<?> deploy(@RequestBody DeployRequest req) { ... }
```

`project` 参数支持三种解析策略：
- 空字符串 `""` — 从 URL 路径变量反查 serviceId → projectId
- `"params.xxx"` — 从请求体的 `xxx` 字段提取（通过反射）
- `"xxx"` — 从 URL 路径变量 `xxx` 提取

**方式二：编程式调用**

```java
// 在 Service 中手动检查
permissionService.checkServicePermission(userId, serviceId, "DEPLOY");
```

### 4.4 权限码清单

| 权限码 | 说明 |
|--------|------|
| `VIEW` | 查看服务列表和状态 |
| `DEPLOY` | 部署/启动服务 |
| `START` | 启动已停止的服务 |
| `STOP` | 停止运行中的服务 |
| `UPLOAD` | 上传 JAR/dist 文件 |
| `EDIT_CONFIG` | 编辑服务配置 |
| `DELETE` | 删除服务 |
| `MANAGE_MEMBERS` | 管理组成员（supervisor 专属） |
| `MANAGE_PROJECTS` | 管理项目（supervisor 专属） |

### 4.5 预设角色

| 角色 | 权限 |
|------|------|
| `viewer` | VIEW |
| `operator` | VIEW, DEPLOY, START, STOP |
| `editor` | VIEW, DEPLOY, START, STOP, UPLOAD, EDIT_CONFIG |
| `admin` | VIEW, DEPLOY, START, STOP, UPLOAD, EDIT_CONFIG, DELETE |
| `supervisor` | 全部权限 + MANAGE_MEMBERS, MANAGE_PROJECTS |

---

## 5. MyBatis-Plus 跨模块配置

### 5.1 Mapper 扫描

`@Mapper` 注解自动注册，不依赖包路径。Spring Boot 启动时扫描所有 JAR 中带 `@Mapper` 的接口。

### 5.2 XML 映射文件

```yaml
mybatis-plus:
  mapper-locations: classpath*:mapper/*.xml
```

`classpath*:` 前缀表示扫描所有 JAR（包括依赖 JAR）中的资源文件，而不仅是主应用自身的 `classpath:`。

### 5.3 实体类别名

```yaml
mybatis-plus:
  type-aliases-package: com.nexa.flowops.entity,com.nexa.flowops.permission.entity
```

必须包含所有模块的实体包路径，否则 XML 中的 `resultType` 简写会找不到类。

---

## 6. POM 配置要点

### 父 POM

```xml
<packaging>pom</packaging>
<modules>
    <module>flowops-common</module>
    <module>flowops-permission</module>
    <module>flowops-app</module>
</modules>
<dependencyManagement>
    <!-- 统一管理版本号 -->
</dependencyManagement>
```

父 POM 不含 `spring-boot-maven-plugin`，只有 `flowops-app` 使用。

### Lombok 配置

每个模块中 Lombok 使用 `<scope>provided</scope>`，防止传递到下游模块：

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <scope>provided</scope>
</dependency>
```

`flowops-app` 的 `maven-compiler-plugin` 需配置 Lombok 注解处理器：

```xml
<annotationProcessorPaths>
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>${lombok.version}</version>
    </path>
</annotationProcessorPaths>
```

---

## 7. 其他项目集成权限模块指南

### 7.1 添加 POM 依赖

```xml
<dependency>
    <groupId>com.nexa</groupId>
    <artifactId>flowops-permission</artifactId>
    <version>1.1.0</version>
</dependency>
```

### 7.2 执行数据库脚本

```sql
-- 1. 建表
source flowops-permission/src/main/resources/sql/permission-schema.sql

-- 2. 种子数据（权限码 + 预设角色）
source flowops-permission/src/main/resources/sql/permission-data.sql
```

### 7.3 实现 ExternalDataProvider

```java
@Component
public class MyExternalDataProvider implements ExternalDataProvider {

    private final MyServiceMapper serviceMapper;

    public MyExternalDataProvider(MyServiceMapper serviceMapper) {
        this.serviceMapper = serviceMapper;
    }

    @Override
    public Long getProjectIdByServiceId(Long serviceId) {
        // 查询你的服务表，返回所属 projectId
        MyService svc = serviceMapper.selectById(serviceId);
        return svc != null ? svc.getProjectId() : null;
    }

    @Override
    public long countServicesByProjectId(Long projectId) {
        return serviceMapper.selectCount(
            new LambdaQueryWrapper<MyService>()
                .eq(MyService::getProjectId, projectId));
    }
}
```

### 7.4 配置 application.yml

```yaml
mybatis-plus:
  mapper-locations: classpath*:mapper/*.xml
  type-aliases-package: com.your.app.entity,com.nexa.flowops.permission.entity

sa-token:
  token-name: Authorization
  token-style: jwt
  jwt-secret-key: ${JWT_SECRET}
  is-stateless: true
```

### 7.5 使用注解校验权限

```java
@RequirePermission("DEPLOY")
@PostMapping("/deploy/{serviceId}")
public Result<?> deploy(@PathVariable Long serviceId) {
    // 有 DEPLOY 权限才会执行到这里
}
```

### 7.6 确保有超级管理员

```sql
UPDATE sys_user SET is_super_admin = 1 WHERE username = 'admin';
```

超级管理员跳过所有权限校验。

---

## 8. 构建与验证

```bash
# 编译全部模块
mvn clean compile

# 打包主应用
mvn clean package -pl flowops-app -DskipTests

# 验证 JAR 内容
jar -tf flowops-app/target/flowops-app-1.1.0.jar | grep permission
```
