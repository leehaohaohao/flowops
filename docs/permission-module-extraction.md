# FlowOps 权限模块抽取设计文档

> 日期：2026/05/24
> 版本：v2（修复审核发现的问题）

---

## 一、目标

将权限系统从当前单模块 Spring Boot 应用中抽取为独立 Maven 模块，使其他项目可以通过 POM 依赖引入使用。

**当前状态：** 单模块 `com.nexa:flowops:1.1.0`，Java 17，Spring Boot 3.3.0，MyBatis-Plus，Sa-Token。

**目标结构：** 三模块 — `flowops-common`（共享工具）→ `flowops-permission`（权限模块）→ `flowops-app`（主应用）。

---

## 二、为什么是三模块而非两模块

`Result.java` 和 `BusinessException.java` 被权限模块和主应用共同使用：

- 放在权限模块 → 概念倒置（主应用为通用工具类依赖权限模块）
- 复制到两个模块 → 类型不兼容（两个不同的 `Result` 类）

`flowops-common` 模块专门存放零业务逻辑的共享代码，解决依赖问题。

**flowops-common 纯净性保证：**
- `Result.java` — 纯 Java POJO，无 Spring 注解
- `BusinessException.java` — 纯 Java 异常类，无 Spring 注解
- `RequirePermission.java` — 纯 Java 注解（`@java.lang.annotation.*`），不触发 Bean 注册
- `RequireGroupSupervisor.java` — 同上
- **该模块不引入任何 Spring 依赖**，不会导致意外 Bean 注册

---

## 三、关键设计决策

### 3.1 打破循环依赖 — 统一 Resolver 模式

`PermissionService` 注入了 `DeployServiceMapper`（主应用的实体）来解析 serviceId → projectId，造成循环依赖。`ProjectService` 同样依赖 `DeployServiceMapper` 来检查项目下是否有服务。

**方案：权限模块定义 `ExternalDataProvider` 接口，主应用提供实现。**

为避免接口粒度过细（每新增一个跨模块查询就改接口），采用**单一聚合接口**：

```java
/**
 * 权限模块对外部数据的查询契约。
 * 集成方必须实现此接口，提供权限模块所需的业务数据。
 *
 * 新增跨模块查询时，在此接口添加默认方法（default method），
 * 默认返回 null/空值，不影响现有实现类。
 */
public interface ExternalDataProvider {

    /** 根据服务 ID 查询所属项目 ID */
    Long getProjectIdByServiceId(Long serviceId);

    /** 统计项目下的服务数量 */
    long countServicesByProjectId(Long projectId);

    // ---- 未来扩展示例 ----
    // 新增查询时添加 default 方法，现有实现无需改动：
    //
    // default Long getProjectIdByDeployRecordId(Long recordId) {
    //     return null; // 默认不支持，调用方需判断
    // }
}
```

**扩展方式：** 新增跨模块查询时，添加 `default` 方法。现有实现类不强制实现，只有需要该功能的集成方才覆写。这避免了频繁改接口的问题。

主应用实现：

```java
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
            new LambdaQueryWrapper<DeployService>().eq(DeployService::getProjectId, projectId));
    }
}
```

### 3.2 包名策略

| 模块 | 包名 | 说明 |
|------|------|------|
| `flowops-common` | `com.nexa.flowops.common` | 不变 |
| `flowops-permission` | `com.nexa.flowops.permission` | 新子包 |
| `flowops-app` | `com.nexa.flowops` | 不变 |

主应用的 `@SpringBootApplication` 默认扫描 `com.nexa.flowops`，自动覆盖 `com.nexa.flowops.permission` 子包，无需额外 `@ComponentScan` 配置。

### 3.3 MyBatis-Plus 跨模块扫描

- `@Mapper` 注解自动注册，不依赖包路径
- `mapper-locations: classpath:mapper/*.xml` 自动扫描所有 JAR 中的 XML 文件
- `type-aliases-package` **必须**添加权限模块的 entity 包路径（详见第六节配置）

### 3.4 AutoFillHandler 跨模块生效

`AutoFillHandler`（MyBatis-Plus `MetaObjectHandler`）在主应用中注册，但对所有模块中带 `@TableField(fill=...)` 注解的实体生效，无需额外配置。

### 3.5 StpInterfaceImpl 可扩展性

`StpInterfaceImpl` 实现 Sa-Token 的 `StpInterface`，负责返回用户的权限列表和角色列表。当前实现硬编码了权限查询逻辑。

**为支持其他项目自定义权限查询方式，提供两种扩展路径：**

1. **覆盖注入（推荐）：** 集成方在自己的 `@Configuration` 类中声明同类型的 `StpInterface` Bean，Spring 按优先级覆盖权限模块的默认实现。
2. **继承覆写：** 集成方继承 `StpInterfaceImpl`，覆写 `getPermissionList` / `getRoleList` 方法，注册为 `@Primary` Bean。

权限模块的 `StpInterfaceImpl` 标注为 `@Component`（非 `@Primary`），集成方可轻松覆盖。

---

## 四、模块依赖关系

```
┌──────────────┐
│ flowops-common │   (纯 Java，无 Spring 依赖)
│  Result        │
│  BusinessException │
│  @RequirePermission │
│  @RequireGroupSupervisor │
└───────┬──────┘
        │
┌───────▼──────────┐
│ flowops-permission │   (权限库 JAR)
│  8 Entity + 8 Mapper │
│  PermissionService    │
│  6 Controller         │
│  PermissionAspect     │
│  PermissionInterceptor│
│  StpInterfaceImpl     │
│  ExternalDataProvider │ (接口)
└───────┬──────────┘
        │
┌───────▼──────────┐
│   flowops-app     │   (Spring Boot 可执行 JAR)
│  DeployService    │
│  AuthController   │
│  ServiceController│
│  DeployController │
│  FlowOpsExternalDataProvider │ (实现)
└──────────────────┘
```

---

## 五、目标目录结构

```
flowops/                              (父 POM, packaging=pom)
├── pom.xml
├── flowops-common/
│   ├── pom.xml
│   └── src/main/java/com/nexa/flowops/common/
│       ├── Result.java
│       ├── BusinessException.java
│       ├── RequirePermission.java
│       └── RequireGroupSupervisor.java
├── flowops-permission/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/nexa/flowops/permission/
│       │   ├── ExternalDataProvider.java          (NEW 聚合接口)
│       │   ├── entity/
│       │   │   ├── SysUser.java
│       │   │   ├── ProjectGroup.java
│       │   │   ├── Project.java
│       │   │   ├── GroupMember.java
│       │   │   ├── PermDefinition.java
│       │   │   ├── PermRole.java
│       │   │   ├── RolePermission.java
│       │   │   └── ProjectAccess.java
│       │   ├── mapper/
│       │   │   ├── SysUserMapper.java
│       │   │   ├── ProjectGroupMapper.java
│       │   │   ├── ProjectMapper.java
│       │   │   ├── GroupMemberMapper.java
│       │   │   ├── PermDefinitionMapper.java
│       │   │   ├── PermRoleMapper.java
│       │   │   ├── RolePermissionMapper.java
│       │   │   └── ProjectAccessMapper.java
│       │   ├── dto/
│       │   │   ├── CreateUserRequest.java
│       │   │   ├── AddMemberRequest.java
│       │   │   ├── UpdateMemberRoleRequest.java
│       │   │   ├── CreateRoleRequest.java
│       │   │   ├── UpdateRoleRequest.java
│       │   │   ├── GrantAccessRequest.java
│       │   │   ├── GroupRequest.java
│       │   │   ├── CreateProjectRequest.java
│       │   │   └── UpdateProjectRequest.java
│       │   ├── service/
│       │   │   ├── PermissionService.java   (改用 ExternalDataProvider)
│       │   │   ├── UserService.java
│       │   │   ├── RoleService.java
│       │   │   ├── MemberService.java
│       │   │   ├── ProjectGroupService.java
│       │   │   └── ProjectService.java      (改用 ExternalDataProvider)
│       │   ├── config/
│       │   │   ├── PermissionAspect.java
│       │   │   ├── PermissionInterceptor.java
│       │   │   ├── StpInterfaceImpl.java    (非 @Primary，可被覆盖)
│       │   │   └── SaTokenConfig.java
│       │   └── controller/
│       │       ├── PermissionController.java
│       │       ├── RoleController.java
│       │       ├── MemberController.java
│       │       ├── ProjectGroupController.java
│       │       ├── ProjectController.java
│       │       └── UserController.java
│       └── resources/
│           └── mapper/
│               └── SysUserMapper.xml
└── flowops-app/
    ├── pom.xml
    └── src/main/
        ├── java/com/nexa/flowops/
        │   ├── FlowopsApplication.java
        │   ├── resolver/
        │   │   └── FlowOpsExternalDataProvider.java  (NEW 实现)
        │   ├── entity/
        │   │   ├── DeployService.java
        │   │   └── DeployRecord.java
        │   ├── mapper/
        │   │   ├── DeployServiceMapper.java
        │   │   └── DeployRecordMapper.java
        │   ├── dto/
        │   │   ├── LoginRequest.java
        │   │   ├── CreateServiceRequest.java
        │   │   └── UpdateServiceRequest.java
        │   ├── service/
        │   │   ├── AuthService.java
        │   │   ├── DashboardService.java
        │   │   ├── ServiceMgmtService.java
        │   │   ├── DeployExecutorService.java
        │   │   └── LogService.java
        │   ├── controller/
        │   │   ├── AuthController.java
        │   │   ├── ServiceController.java
        │   │   ├── DeployController.java
        │   │   ├── LogController.java
        │   │   └── StatsController.java
        │   ├── config/
        │   │   ├── AutoFillHandler.java
        │   │   ├── CorsFilter.java
        │   │   ├── DotenvPostProcessor.java
        │   │   ├── GlobalExceptionHandler.java
        │   │   ├── TraceFilter.java
        │   │   └── WebSocketConfig.java
        │   ├── util/
        │   │   └── DockerUtil.java
        │   └── ws/
        │       ├── ContainerLogWebSocketHandler.java
        │       └── LogWebSocketHandler.java
        └── resources/
            ├── application.yml
            ├── application-dev.yml
            ├── application-prod.yml
            ├── logback-spring.xml
            └── sql/
                ├── init.sql
                └── migration.sql
```

---

## 六、POM 配置要点

### 6.1 父 POM (`flowops/pom.xml`)

```xml
<packaging>pom</packaging>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.0</version>
</parent>

<modules>
    <module>flowops-common</module>
    <module>flowops-permission</module>
    <module>flowops-app</module>
</modules>

<properties>
    <java.version>17</java.version>
    <sa-token.version>1.37.0</sa-token.version>
    <mybatis-plus.version>3.5.6</mybatis-plus.version>
    <dotenv.version>3.0.0</dotenv.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-spring-boot3-starter</artifactId>
            <version>${sa-token.version}</version>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.cdimascio</groupId>
            <artifactId>dotenv-java</artifactId>
            <version>${dotenv.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- 无 spring-boot-maven-plugin（只有 app 模块使用）-->
```

### 6.2 `flowops-common/pom.xml`

```xml
<packaging>jar</packaging>

<dependencies>
    <!-- Lombok: 仅编译期使用，不传递给下游 -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

> **说明：** 使用 `<scope>provided</scope>` 而非 `<optional>true</optional>`。
> `provided` 表示编译时需要但运行时由容器提供，不会传递给下游模块。
> 下游模块如果需要 Lombok（如使用 `@Data`、`@Builder`），必须自行声明依赖。

### 6.3 `flowops-permission/pom.xml`

```xml
<packaging>jar</packaging>  <!-- 库 JAR，非可执行 -->

<dependencies>
    <dependency>
        <groupId>com.nexa</groupId>
        <artifactId>flowops-common</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>
    <dependency>
        <groupId>cn.dev33</groupId>
        <artifactId>sa-token-spring-boot3-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>

<!-- 无 spring-boot-maven-plugin -->
```

### 6.4 `flowops-app/pom.xml`

```xml
<packaging>jar</packaging>

<dependencies>
    <dependency>
        <groupId>com.nexa</groupId>
        <artifactId>flowops-common</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>com.nexa</groupId>
        <artifactId>flowops-permission</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
    </dependency>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>cn.dev33</groupId>
        <artifactId>sa-token-spring-boot3-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.github.cdimascio</groupId>
        <artifactId>dotenv-java</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </exclude>
                </excludes>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 6.5 `application.yml` 配置变更

```yaml
mybatis-plus:
  # 扫描所有模块的 Mapper XML
  mapper-locations: classpath*:mapper/*.xml
  # 必须包含权限模块的 entity 包路径
  type-aliases-package: com.nexa.flowops.entity,com.nexa.flowops.permission.entity
  configuration:
    map-underscore-to-camel-case: true
```

> **注意：** `classpath*:` 前缀（带星号）确保扫描所有 JAR 中的资源，包括依赖模块。不加星号只扫描第一个匹配的 JAR。

---

## 七、DDL 归属与集成指引

### 7.1 DDL 归属

权限相关的 DDL（建表语句）**归属于权限模块**，随模块一起维护：

```
flowops-permission/
  src/main/resources/
    sql/
      permission-schema.sql      -- 权限相关表结构（CREATE TABLE）
      permission-data.sql        -- 种子数据（预设角色、权限定义）
    mapper/
      SysUserMapper.xml
```

集成方引入权限模块后，需要：
1. 执行 `permission-schema.sql` 创建权限相关表
2. 执行 `permission-data.sql` 插入种子数据
3. 执行自己业务表的建表语句

### 7.2 集成方完整接入步骤

```xml
<!-- 1. POM 依赖 -->
<dependency>
    <groupId>com.nexa</groupId>
    <artifactId>flowops-permission</artifactId>
    <version>1.1.0</version>
</dependency>
```

```java
// 2. 实现 ExternalDataProvider
@Component
public class MyExternalDataProvider implements ExternalDataProvider {
    @Override
    public Long getProjectIdByServiceId(Long serviceId) {
        // 查自己的服务表
        return myServiceMapper.selectById(serviceId).getProjectId();
    }

    @Override
    public long countServicesByProjectId(Long projectId) {
        return myServiceMapper.selectCount(...);
    }
}
```

```yaml
# 3. application.yml
mybatis-plus:
  mapper-locations: classpath*:mapper/*.xml
  type-aliases-package: com.your.project.entity,com.nexa.flowops.permission.entity

sa-token:
  token-name: Authorization
  timeout: 86400
  token-style: jwt
  is-stateless: true
```

```sql
-- 4. 执行 DDL
source permission-schema.sql;
source permission-data.sql;
```

```java
// 5. 使用注解保护接口
@RestController
public class YourController {

    @RequirePermission("DEPLOY")
    @PostMapping("/api/your/deploy/{id}")
    public Result<?> deploy(@PathVariable Long id) { ... }
}

// 6. 查询权限
@Service
public class YourService {
    private final PermissionService permissionService;

    public void checkAccess(Long userId, Long projectId) {
        Set<String> perms = permissionService.getEffectivePermissions(userId, projectId);
        if (!perms.contains("VIEW")) {
            throw new BusinessException("无查看权限");
        }
    }
}
```

---

## 八、实施步骤

### Phase 1: 创建 POM 结构
1. 转换现有 `pom.xml` 为父 POM
2. 创建 `flowops-common/pom.xml`
3. 创建 `flowops-permission/pom.xml`
4. 创建 `flowops-app/pom.xml`

### Phase 2: 创建 flowops-common
5. 移动 `Result.java`, `BusinessException.java`, `RequirePermission.java`, `RequireGroupSupervisor.java`

### Phase 3: 创建 flowops-permission
6. 创建 `ExternalDataProvider` 接口
7. 移动 8 个 entity（更新 package 声明）
8. 移动 8 个 mapper（更新 package + imports）
9. 移动 `SysUserMapper.xml`（更新 namespace + resultType）
10. 移动 9 个 DTO
11. 移动 6 个 service（PermissionService 和 ProjectService 改用 ExternalDataProvider）
12. 移动 4 个 config
13. 移动 6 个 controller
14. 创建 `sql/permission-schema.sql` 和 `sql/permission-data.sql`

### Phase 4: 更新 flowops-app
15. 创建 `FlowOpsExternalDataProvider`（实现 ExternalDataProvider）
16. 批量更新 import 路径（见第九节方案）
17. 更新 `application.yml`（type-aliases-package + mapper-locations）

### Phase 5: 验证
18. `mvn clean compile` 三模块编译通过
19. `mvn clean package -pl flowops-app` 构建可执行 JAR
20. 启动应用，测试全部 API

---

## 九、批量 Import 替换方案

改造涉及上百个文件的 import 路径变更。以下是批量替换策略：

### 9.1 替换映射表

| 原路径 | 新路径 |
|--------|--------|
| `com.nexa.flowops.entity.SysUser` | `com.nexa.flowops.permission.entity.SysUser` |
| `com.nexa.flowops.entity.ProjectGroup` | `com.nexa.flowops.permission.entity.ProjectGroup` |
| `com.nexa.flowops.entity.Project` | `com.nexa.flowops.permission.entity.Project` |
| `com.nexa.flowops.entity.GroupMember` | `com.nexa.flowops.permission.entity.GroupMember` |
| `com.nexa.flowops.entity.PermDefinition` | `com.nexa.flowops.permission.entity.PermDefinition` |
| `com.nexa.flowops.entity.PermRole` | `com.nexa.flowops.permission.entity.PermRole` |
| `com.nexa.flowops.entity.RolePermission` | `com.nexa.flowops.permission.entity.RolePermission` |
| `com.nexa.flowops.entity.ProjectAccess` | `com.nexa.flowops.permission.entity.ProjectAccess` |
| `com.nexa.flowops.mapper.SysUserMapper` | `com.nexa.flowops.permission.mapper.SysUserMapper` |
| `com.nexa.flowops.mapper.ProjectGroupMapper` | `com.nexa.flowops.permission.mapper.ProjectGroupMapper` |
| `com.nexa.flowops.mapper.ProjectMapper` | `com.nexa.flowops.permission.mapper.ProjectMapper` |
| `com.nexa.flowops.mapper.GroupMemberMapper` | `com.nexa.flowops.permission.mapper.GroupMemberMapper` |
| `com.nexa.flowops.mapper.PermDefinitionMapper` | `com.nexa.flowops.permission.mapper.PermDefinitionMapper` |
| `com.nexa.flowops.mapper.PermRoleMapper` | `com.nexa.flowops.permission.mapper.PermRoleMapper` |
| `com.nexa.flowops.mapper.RolePermissionMapper` | `com.nexa.flowops.permission.mapper.RolePermissionMapper` |
| `com.nexa.flowops.mapper.ProjectAccessMapper` | `com.nexa.flowops.permission.mapper.ProjectAccessMapper` |
| `com.nexa.flowops.service.PermissionService` | `com.nexa.flowops.permission.service.PermissionService` |
| `com.nexa.flowops.service.UserService` | `com.nexa.flowops.permission.service.UserService` |
| `com.nexa.flowops.service.RoleService` | `com.nexa.flowops.permission.service.RoleService` |
| `com.nexa.flowops.service.MemberService` | `com.nexa.flowops.permission.service.MemberService` |
| `com.nexa.flowops.service.ProjectGroupService` | `com.nexa.flowops.permission.service.ProjectGroupService` |
| `com.nexa.flowops.service.ProjectService` | `com.nexa.flowops.permission.service.ProjectService` |
| `com.nexa.flowops.dto.CreateUserRequest` | `com.nexa.flowops.permission.dto.CreateUserRequest` |
| `com.nexa.flowops.dto.AddMemberRequest` | `com.nexa.flowops.permission.dto.AddMemberRequest` |
| `com.nexa.flowops.dto.UpdateMemberRoleRequest` | `com.nexa.flowops.permission.dto.UpdateMemberRoleRequest` |
| `com.nexa.flowops.dto.CreateRoleRequest` | `com.nexa.flowops.permission.dto.CreateRoleRequest` |
| `com.nexa.flowops.dto.UpdateRoleRequest` | `com.nexa.flowops.permission.dto.UpdateRoleRequest` |
| `com.nexa.flowops.dto.GrantAccessRequest` | `com.nexa.flowops.permission.dto.GrantAccessRequest` |
| `com.nexa.flowops.dto.GroupRequest` | `com.nexa.flowops.permission.dto.GroupRequest` |
| `com.nexa.flowops.dto.CreateProjectRequest` | `com.nexa.flowops.permission.dto.CreateProjectRequest` |
| `com.nexa.flowops.dto.UpdateProjectRequest` | `com.nexa.flowops.permission.dto.UpdateProjectRequest` |

### 9.2 执行方式

使用 `find + sed` 批量替换（在 Git Bash 或 Linux 环境执行）：

```bash
# 在项目根目录执行
find . -name "*.java" -exec sed -i \
  -e 's/com\.nexa\.flowops\.entity\.SysUser/com.nexa.flowops.permission.entity.SysUser/g' \
  -e 's/com\.nexa\.flowops\.entity\.ProjectGroup/com.nexa.flowops.permission.entity.ProjectGroup/g' \
  -e 's/com\.nexa\.flowops\.entity\.Project\b/com.nexa.flowops.permission.entity.Project/g' \
  -e 's/com\.nexa\.flowops\.entity\.GroupMember/com.nexa.flowops.permission.entity.GroupMember/g' \
  -e 's/com\.nexa\.flowops\.entity\.PermDefinition/com.nexa.flowops.permission.entity.PermDefinition/g' \
  -e 's/com\.nexa\.flowops\.entity\.PermRole/com.nexa.flowops.permission.entity.PermRole/g' \
  -e 's/com\.nexa\.flowops\.entity\.RolePermission/com.nexa.flowops.permission.entity.RolePermission/g' \
  -e 's/com\.nexa\.flowops\.entity\.ProjectAccess/com.nexa.flowops.permission.entity.ProjectAccess/g' \
  -e 's/com\.nexa\.flowops\.mapper\.SysUserMapper/com.nexa.flowops.permission.mapper.SysUserMapper/g' \
  -e 's/com\.nexa\.flowops\.mapper\.ProjectGroupMapper/com.nexa.flowops.permission.mapper.ProjectGroupMapper/g' \
  -e 's/com\.nexa\.flowops\.mapper\.ProjectMapper/com.nexa.flowops.permission.mapper.ProjectMapper/g' \
  -e 's/com\.nexa\.flowops\.mapper\.GroupMemberMapper/com.nexa.flowops.permission.mapper.GroupMemberMapper/g' \
  -e 's/com\.nexa\.flowops\.mapper\.PermDefinitionMapper/com.nexa.flowops.permission.mapper.PermDefinitionMapper/g' \
  -e 's/com\.nexa\.flowops\.mapper\.PermRoleMapper/com.nexa.flowops.permission.mapper.PermRoleMapper/g' \
  -e 's/com\.nexa\.flowops\.mapper\.RolePermissionMapper/com.nexa.flowops.permission.mapper.RolePermissionMapper/g' \
  -e 's/com\.nexa\.flowops\.mapper\.ProjectAccessMapper/com.nexa.flowops.permission.mapper.ProjectAccessMapper/g' \
  -e 's/com\.nexa\.flowops\.service\.PermissionService/com.nexa.flowops.permission.service.PermissionService/g' \
  -e 's/com\.nexa\.flowops\.service\.UserService/com.nexa.flowops.permission.service.UserService/g' \
  -e 's/com\.nexa\.flowops\.service\.RoleService/com.nexa.flowops.permission.service.RoleService/g' \
  -e 's/com\.nexa\.flowops\.service\.MemberService/com.nexa.flowops.permission.service.MemberService/g' \
  -e 's/com\.nexa\.flowops\.service\.ProjectGroupService/com.nexa.flowops.permission.service.ProjectGroupService/g' \
  -e 's/com\.nexa\.flowops\.service\.ProjectService/com.nexa.flowops.permission.service.ProjectService/g' \
  -e 's/com\.nexa\.flowops\.dto\./com.nexa.flowops.permission.dto./g' \
  {} \;
```

> **注意：** `Project\b` 使用词边界避免误匹配 `ProjectGroup`、`ProjectAccess` 等。
> 替换后需人工 review 确认无误，再执行 `mvn compile` 验证。

### 9.3 替换顺序

1. 先移动文件到新目录（保留原文件作为备份）
2. 执行批量 import 替换
3. `mvn compile` 验证编译通过
4. 确认无误后删除原目录中的旧文件

---

## 十、需要重构的关键代码

### 10.1 PermissionService — 替换 DeployServiceMapper

```java
// 改前：
private final DeployServiceMapper deployServiceMapper;

public Long getProjectIdByServiceId(Long serviceId) {
    DeployService service = deployServiceMapper.selectById(serviceId);
    return service != null ? service.getProjectId() : null;
}

// 改后：
private final ExternalDataProvider externalDataProvider;

public Long getProjectIdByServiceId(Long serviceId) {
    return externalDataProvider.getProjectIdByServiceId(serviceId);
}
```

### 10.2 ProjectService — 替换 DeployServiceMapper

```java
// 改前：
private final DeployServiceMapper deployServiceMapper;

public void delete(Long id) {
    long count = deployServiceMapper.selectCount(
        new LambdaQueryWrapper<DeployService>().eq(DeployService::getProjectId, id));
    if (count > 0) throw new BusinessException("项目下有服务，无法删除");
    ...
}

// 改后：
private final ExternalDataProvider externalDataProvider;

public void delete(Long id) {
    long count = externalDataProvider.countServicesByProjectId(id);
    if (count > 0) throw new BusinessException("项目下有服务，无法删除");
    ...
}
```

### 10.3 SysUserMapper.xml 更新

```xml
<!-- 改前 -->
<mapper namespace="com.nexa.flowops.mapper.SysUserMapper">
    <select id="selectByUsername" resultType="com.nexa.flowops.entity.SysUser">

<!-- 改后 -->
<mapper namespace="com.nexa.flowops.permission.mapper.SysUserMapper">
    <select id="selectByUsername" resultType="com.nexa.flowops.permission.entity.SysUser">
```

---

## 十一、注意事项

1. **Spring Boot 版本必须一致** — 权限模块和主应用必须使用相同的 Spring Boot 版本，否则 Sa-Token / MyBatis-Plus 可能出现兼容问题。

2. **Lombok 每个模块独立声明** — `flowops-common` 使用 `<scope>provided</scope>`，不传递给下游。`flowops-permission` 和 `flowops-app` 各自声明 Lombok 依赖。

3. **mapper-locations 使用 `classpath*:`** — 带星号扫描所有 JAR 中的资源，不加只扫描第一个匹配。

4. **type-aliases-package 必须包含权限模块路径** — 否则 XML 中的 `resultType` 短类名无法解析。

5. **Sa-Token 配置在主应用中** — `SaTokenConfig` 在权限模块中定义了拦截器注册逻辑，但 `application.yml` 中的 `sa-token.*` 配置项必须在主应用中提供。

6. **权限模块不含 `@SpringBootApplication`** — 它是库 JAR，Spring Bean 由主应用的组件扫描自动发现。

7. **权限模块的 DDL 随模块分发** — 集成方需执行 `permission-schema.sql` 和 `permission-data.sql`。
