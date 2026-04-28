# FlowOps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个轻量级 CI/CD 部署平台，支持多用户管理、服务管理、Docker 容器部署、实时日志查看

**Architecture:** 单体 Spring Boot 应用，使用 Sa-Token 做权限控制，Docker Java SDK 操作容器，前端 Thymeleaf 模板渲染

**Tech Stack:** Spring Boot 3 + Sa-Token + MySQL + Docker SDK + Thymeleaf + Bootstrap

---

## 文件结构

```
src/main/java/com/nexa/flowops/
├── FlowopsApplication.java
├── config/
│   └── SaTokenConfig.java          # Sa-Token 配置
├── controller/
│   ├── AuthController.java         # 登录/登出
│   ├── UserController.java         # 用户管理
│   ├── ServiceController.java      # 服务管理
│   ├── DeployController.java       # 部署操作
│   └── LogController.java          # 日志查看
├── entity/
│   ├── SysUser.java                # 用户实体
│   ├── DeployService.java          # 服务实体
│   └── DeployRecord.java           # 部署记录实体
├── mapper/
│   ├── SysUserMapper.java
│   ├── DeployServiceMapper.java
│   └── DeployRecordMapper.java
├── service/
│   ├── AuthService.java
│   ├── UserService.java
│   ├── ServiceMgmtService.java
│   ├── DeployService.java          # 部署核心逻辑
│   └── LogService.java
├── dto/
│   ├── LoginRequest.java
│   ├── ServiceCreateRequest.java
│   └── DeployRequest.java
└── util/
    └── DockerUtil.java             # Docker 操作工具类

src/main/resources/
├── application.properties
├── templates/
│   ├── layout.html                 # 基础布局
│   ├── login.html
│   ├── dashboard.html              # 首页/仪表盘
│   ├── user-list.html
│   ├── service-list.html
│   ├── service-edit.html
│   └── deploy-logs.html
└── static/
    ├── css/
    └── js/
```

---

## 实现任务

### Task 1: 项目依赖配置

**Files:**
- Modify: `pom.xml:32-48`

- [ ] **Step 1: 添加必要依赖**

在 `pom.xml` 的 `<dependencies>` 中添加：

```xml
<!-- Sa-Token 权限认证 -->
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot3-starter</artifactId>
    <version>1.37.0</version>
</dependency>

<!-- MySQL 驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- MyBatis-Plus -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.6</version>
</dependency>

<!-- Docker SDK -->
<dependency>
    <groupId>com.github.docker-java</groupId>
    <artifactId>docker-java</artifactId>
    <version>3.3.6</version>
</dependency>

<!-- WebSocket -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>

<!-- Thymeleaf -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```

- [ ] **Step 2: 提交**

```bash
git add pom.xml && git commit -m "feat: 添加 Sa-Token, MyBatis-Plus, Docker SDK, WebSocket 依赖"
```

---

### Task 2: 配置文件

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 配置数据库和 Sa-Token**

```yaml
# 服务配置
server:
  port: 8080

# 数据库配置
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/flowops?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver

# MyBatis-Plus 配置
mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.nexa.flowops.entity
  configuration:
    map-underscore-to-camel-case: true

# Sa-Token 配置
sa-token:
  token-name: satoken
  timeout: 86400
  active-timeout: -1
  is-concurrent: true
  is-share: true
  token-style: uuid
  is-log: false

# Docker 配置
docker:
  host: tcp://localhost:2375
  registry-url: https://index.docker.io/v1/

# 产物存储目录
app:
  storage:
    path: /data/flowops/services
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/application.yml && git commit -m "feat: 添加数据库和 Sa-Token 配置"
```

---

### Task 3: 数据库实体类

**Files:**
- Create: `src/main/java/com/nexa/flowops/entity/SysUser.java`
- Create: `src/main/java/com/nexa/flowops/entity/DeployService.java`
- Create: `src/main/java/com/nexa/flowops/entity/DeployRecord.java`

- [ ] **Step 1: 创建 SysUser 实体**

```java
package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String role;  // admin, user
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
```

- [ ] **Step 2: 创建 DeployService 实体**

```java
package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("deploy_service")
public class DeployService {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;           // 服务名称
    private Integer port;           // 暴露端口
    private String volumeDir;       // 挂载目录
    private String dockerfile;      // Dockerfile 内容
    private String dockerCompose;  // docker-compose.yml 内容
    private String status;          // running, stopped
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

- [ ] **Step 3: 创建 DeployRecord 实体**

```java
package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("deploy_record")
public class DeployRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long serviceId;
    private String status;         // success, failed
    private String logPath;        // 日志文件路径
    private String remark;         // 备注
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/nexa/flowops/entity/*.java && git commit -m "feat: 添加用户、服务、部署记录实体类"
```

---

### Task 4: Sa-Token 权限配置

**Files:**
- Create: `src/main/java/com/nexa/flowops/config/SaTokenConfig.java`

- [ ] **Step 1: 创建 SaTokenConfig**

```java
package com.nexa.flowops.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/captcha",
                        "/error"
                );
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/nexa/flowops/config/SaTokenConfig.java && git commit -m "feat: 添加 Sa-Token 权限配置"
```

---

### Task 5: 认证服务

**Files:**
- Create: `src/main/java/com/nexa/flowops/controller/AuthController.java`
- Create: `src/main/java/com/nexa/flowops/service/AuthService.java`

- [ ] **Step 1: 创建 AuthController**

```java
package com.nexa.flowops.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> params) {
        String username = params.get("username");
        String password = params.get("password");
        Map<String, Object> result = new HashMap<>();
        if (authService.login(username, password)) {
            StpUtil.login(username);
            result.put("code", 200);
            result.put("msg", "登录成功");
            result.put("data", StpUtil.getTokenValue());
        } else {
            result.put("code", 401);
            result.put("msg", "用户名或密码错误");
        }
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        StpUtil.logout();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "退出成功");
        return result;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of(
                "username", StpUtil.getLoginIdAsString(),
                "role", StpUtil.hasRole("admin") ? "admin" : "user"
        ));
        return result;
    }
}
```

- [ ] **Step 2: 创建 AuthService**

```java
package com.nexa.flowops.service;

import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final SysUserMapper userMapper;

    public AuthService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public boolean login(String username, String password) {
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            return false;
        }
        // 简单密码校验，生产环境应使用 BCrypt
        return user.getPassword().equals(password);
    }
}
```

- [ ] **Step 3: 创建 SysUserMapper**

```java
package com.nexa.flowops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexa.flowops.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    SysUser selectByUsername(String username);
}
```

- [ ] **Step 4: 创建 SysUserMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.nexa.flowops.mapper.SysUserMapper">
    <select id="selectByUsername" resultType="com.nexa.flowops.entity.SysUser">
        SELECT * FROM sys_user WHERE username = #{username}
    </select>
</mapper>
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/nexa/flowops/controller/AuthController.java src/main/java/com/nexa/flowops/service/AuthService.java src/main/java/com/nexa/flowops/mapper/SysUserMapper.java src/main/resources/mapper/SysUserMapper.xml && git commit -m "feat: 添加登录认证功能"
```

---

### Task 6: 用户管理

**Files:**
- Create: `src/main/java/com/nexa/flowops/controller/UserController.java`
- Create: `src/main/java/com/nexa/flowops/service/UserService.java`
- Create: `src/main/java/com/nexa/flowops/mapper/DeployServiceMapper.java`

- [ ] **Step 1: 创建 UserController**

```java
package com.nexa.flowops.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nexa.flowops.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@SaCheckRole("admin")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of("code", 200, "data", userService.list());
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody Map<String, String> params) {
        userService.createUser(params.get("username"), params.get("password"), params.get("role"));
        return Map.of("code", 200, "msg", "创建成功");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Map.of("code", 200, "msg", "删除成功");
    }
}
```

- [ ] **Step 2: 创建 UserService**

```java
package com.nexa.flowops.service;

import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final SysUserMapper userMapper;

    public UserService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<SysUser> list() {
        return userMapper.selectList(null);
    }

    public void createUser(String username, String password, String role) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(password); // 生产环境应加密
        user.setRole(role);
        userMapper.insert(user);
    }

    public void deleteUser(Long id) {
        userMapper.deleteById(id);
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/nexa/flowops/controller/UserController.java src/main/java/com/nexa/flowops/service/UserService.java && git commit -m "feat: 添加用户管理功能"
```

---

### Task 7: 服务管理

**Files:**
- Create: `src/main/java/com/nexa/flowops/controller/ServiceController.java`
- Create: `src/main/java/com/nexa/flowops/service/ServiceMgmtService.java`
- Create: `src/main/java/com/nexa/flowops/mapper/DeployServiceMapper.java`
- Create: `src/main/resources/mapper/DeployServiceMapper.xml`

- [ ] **Step 1: 创建 ServiceController**

```java
package com.nexa.flowops.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nexa.flowops.service.ServiceMgmtService;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;

import java.util.Map;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceMgmtService serviceMgmtService;

    public ServiceController(ServiceMgmtService serviceMgmtService) {
        this.serviceMgmtService = serviceMgmtService;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of("code", 200, "data", serviceMgmtService.list());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return Map.of("code", 200, "data", serviceMgmtService.getById(id));
    }

    @PostMapping("/create")
    @SaCheckRole("admin")
    public Map<String, Object> create(@RequestBody Map<String, Object> params) {
        serviceMgmtService.createService(params);
        return Map.of("code", 200, "msg", "创建成功");
    }

    @PutMapping("/{id}")
    @SaCheckRole("admin")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        serviceMgmtService.updateService(id, params);
        return Map.of("code", 200, "msg", "更新成功");
    }

    @DeleteMapping("/{id}")
    @SaCheckRole("admin")
    public Map<String, Object> delete(@PathVariable Long id) {
        serviceMgmtService.deleteService(id);
        return Map.of("code", 200, "msg", "删除成功");
    }
}
```

- [ ] **Step 2: 创建 ServiceMgmtService**

```java
package com.nexa.flowops.service;

import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Map;

@Service
public class ServiceMgmtService {

    private final DeployServiceMapper serviceMapper;
    private final String storagePath = "/data/flowops/services";

    public ServiceMgmtService(DeployServiceMapper serviceMapper) {
        this.serviceMapper = serviceMapper;
    }

    public List<DeployService> list() {
        return serviceMapper.selectList(null);
    }

    public DeployService getById(Long id) {
        return serviceMapper.selectById(id);
    }

    public void createService(Map<String, Object> params) {
        DeployService service = new DeployService();
        service.setName((String) params.get("name"));
        service.setPort((Integer) params.get("port"));
        service.setVolumeDir(storagePath + "/" + service.getName());
        service.setDockerfile((String) params.get("dockerfile"));
        service.setDockerCompose((String) params.get("dockerCompose"));
        service.setStatus("stopped");
        serviceMapper.insert(service);

        // 创建服务目录
        new File(service.getVolumeDir()).mkdirs();
    }

    public void updateService(Long id, Map<String, Object> params) {
        DeployService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new RuntimeException("服务不存在");
        }
        if (params.containsKey("name")) {
            service.setName((String) params.get("name"));
        }
        if (params.containsKey("port")) {
            service.setPort((Integer) params.get("port"));
        }
        if (params.containsKey("dockerfile")) {
            service.setDockerfile((String) params.get("dockerfile"));
        }
        if (params.containsKey("dockerCompose")) {
            service.setDockerCompose((String) params.get("dockerCompose"));
        }
        serviceMapper.updateById(service);
    }

    public void deleteService(Long id) {
        serviceMapper.deleteById(id);
    }
}
```

- [ ] **Step 3: 创建 DeployServiceMapper**

```java
package com.nexa.flowops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexa.flowops.entity.DeployService;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeployServiceMapper extends BaseMapper<DeployService> {
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/nexa/flowops/controller/ServiceController.java src/main/java/com/nexa/flowops/service/ServiceMgmtService.java src/main/java/com/nexa/flowops/mapper/DeployServiceMapper.java && git commit -m "feat: 添加服务管理功能"
```

---

### Task 8: 文件上传和部署核心逻辑

**Files:**
- Create: `src/main/java/com/nexa/flowops/controller/DeployController.java`
- Create: `src/main/java/com/nexa/flowops/service/DeployService.java`
- Create: `src/main/java/com/nexa/flowops/util/DockerUtil.java`
- Create: `src/main/java/com/nexa/flowops/mapper/DeployRecordMapper.java`

- [ ] **Step 1: 创建 DeployController**

```java
package com.nexa.flowops.controller;

import com.nexa.flowops.service.DeployService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/deploy")
public class DeployController {

    private final DeployService deployService;

    public DeployController(DeployService deployService) {
        this.deployService = deployService;
    }

    @PostMapping("/upload/{serviceId}")
    public Map<String, Object> upload(
            @PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "jar") String type) {
        String filename = file.getOriginalFilename();
        String uploadPath = deployService.getUploadPath(serviceId, type);
        try {
            File targetFile = new File(uploadPath, filename);
            file.transferTo(targetFile);
            return Map.of("code", 200, "msg", "上传成功", "filename", filename);
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/upload-dist/{serviceId}")
    public Map<String, Object> uploadDist(
            @PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file) {
        String uploadPath = deployService.getUploadPath(serviceId, "dist");
        try {
            // 解压 dist 到目标目录
            deployService.extractDist(file, uploadPath);
            return Map.of("code", 200, "msg", "前端文件上传成功");
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/start/{serviceId}")
    public Map<String, Object> start(@PathVariable Long serviceId) {
        return deployService.deploy(serviceId);
    }

    @PostMapping("/stop/{serviceId}")
    public Map<String, Object> stop(@PathVariable Long serviceId) {
        return deployService.stopContainer(serviceId);
    }

    @GetMapping("/status/{serviceId}")
    public Map<String, Object> status(@PathVariable Long serviceId) {
        return deployService.getContainerStatus(serviceId);
    }
}
```

- [ ] **Step 2: 创建 DeployService (核心部署逻辑)**

```java
package com.nexa.flowops.service;

import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.util.DockerUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

@Service
public class DeployService {

    private final DeployServiceMapper serviceMapper;
    private final DeployRecordMapper recordMapper;
    private final DockerUtil dockerUtil;
    private final String storagePath = "/data/flowops/services";

    public DeployService(DeployServiceMapper serviceMapper, DeployRecordMapper recordMapper, DockerUtil dockerUtil) {
        this.serviceMapper = serviceMapper;
        this.recordMapper = recordMapper;
        this.dockerUtil = dockerUtil;
    }

    public String getUploadPath(Long serviceId, String type) {
        DeployService service = serviceMapper.selectById(serviceId);
        String path = service.getVolumeDir();
        if ("dist".equals(type)) {
            path = path + "/dist";
        }
        return path;
    }

    public void extractDist(MultipartFile file, String targetDir) throws IOException {
        File dir = new File(targetDir);
        if (dir.exists()) {
            dir.delete();
        }
        dir.mkdirs();

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            byte[] buffer = new byte[1024];
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    public Map<String, Object> deploy(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        DeployRecord record = new DeployRecord();
        record.setServiceId(serviceId);
        record.setCreateTime(LocalDateTime.now());

        String logPath = storagePath + "/logs/" + service.getName() + "-" + System.currentTimeMillis() + ".log";

        try {
            // 生成 docker-compose.yml
            String composeContent = service.getDockerCompose();
            if (composeContent == null || composeContent.isEmpty()) {
                composeContent = generateDefaultCompose(service);
            }
            File composeFile = new File(service.getVolumeDir(), "docker-compose.yml");
            Files.writeString(composeFile.toPath(), composeContent);

            // 执行部署
            ProcessBuilder pb = new ProcessBuilder(
                    "docker-compose", "-f", composeFile.getAbsolutePath(), "down"
            );
            pb.directory(new File(service.getVolumeDir()));
            pb.start().waitFor();

            pb = new ProcessBuilder(
                    "docker-compose", "-f", composeFile.getAbsolutePath(), "up", "-d", "--build"
            );
            pb.directory(new File(service.getVolumeDir()));
            Process process = pb.start();

            // 记录日志
            Files.createDirectories(new File(logPath).getParentFile());
            try (InputStream is = process.getInputStream();
                 OutputStream os = new FileOutputStream(logPath)) {
                is.transferTo(os);
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                record.setStatus("success");
                service.setStatus("running");
            } else {
                record.setStatus("failed");
                service.setStatus("stopped");
            }
            serviceMapper.updateById(service);
            recordMapper.insert(record);

            return Map.of("code", 200, "msg", exitCode == 0 ? "部署成功" : "部署失败");
        } catch (Exception e) {
            record.setStatus("failed");
            recordMapper.insert(record);
            return Map.of("code", 500, "msg", "部署异常: " + e.getMessage());
        }
    }

    private String generateDefaultCompose(DeployService service) {
        return """
                version: '3'
                services:
                  app:
                    build: .
                    ports:
                      - "%d:8080"
                    volumes:
                      - ./app.jar:/app/app.jar
                    restart: unless-stopped
                """.formatted(service.getPort());
    }

    public Map<String, Object> stopContainer(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker-compose", "-f", service.getVolumeDir() + "/docker-compose.yml", "down"
            );
            pb.directory(new File(service.getVolumeDir()));
            pb.start().waitFor();
            service.setStatus("stopped");
            serviceMapper.updateById(service);
            return Map.of("code", 200, "msg", "停止成功");
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "停止失败: " + e.getMessage());
        }
    }

    public Map<String, Object> getContainerStatus(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        boolean running = dockerUtil.isContainerRunning(service.getName());
        return Map.of("code", 200, "data", Map.of("running", running, "status", running ? "running" : "stopped"));
    }
}
```

- [ ] **Step 3: 创建 DockerUtil**

```java
package com.nexa.flowops.util;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.InfoCmdImpl;
import org.springframework.stereotype.Component;

@Component
public class DockerUtil {

    private final DockerClient dockerClient;

    public DockerUtil() {
        this.dockerClient = DockerClientImpl.getInstance("tcp://localhost:2375");
    }

    public boolean isContainerRunning(String containerName) {
        try {
            return dockerClient.listContainersCmd()
                    .withShowAll(false)
                    .exec()
                    .stream()
                    .anyMatch(c -> c.getNames()[0].equals("/" + containerName));
        } catch (Exception e) {
            return false;
        }
    }

    public String getContainerLog(String containerName, long tail) {
        try {
            return dockerClient.logContainerCmd(containerName)
                    .withTail(tail)
                    .exec(new org.apache.commons.io.output.StringBuilderWriter())
                    .toString();
        } catch (Exception e) {
            return "获取日志失败: " + e.getMessage();
        }
    }
}
```

- [ ] **Step 4: 创建 DeployRecordMapper**

```java
package com.nexa.flowops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexa.flowops.entity.DeployRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeployRecordMapper extends BaseMapper<DeployRecord> {
}
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/nexa/flowops/controller/DeployController.java src/main/java/com/nexa/flowops/service/DeployService.java src/main/java/com/nexa/flowops/util/DockerUtil.java src/main/java/com/nexa/flowops/mapper/DeployRecordMapper.java && git commit -m "feat: 添加部署核心功能和 Docker 操作工具"
```

---

### Task 9: 日志服务 (WebSocket)

**Files:**
- Create: `src/main/java/com/nexa/flowops/config/WebSocketConfig.java`
- Create: `src/main/java/com/nexa/flowops/controller/LogController.java`
- Create: `src/main/java/com/nexa/flowops/service/LogService.java`

- [ ] **Step 1: 创建 WebSocketConfig**

```java
package com.nexa.flowops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.nexa.flowops.service.LogService;
import org.springframework.web.socket.handler.TextMessageHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LogService logService;

    public WebSocketConfig(LogService logService) {
        this.logService = logService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TextMessageHandler(), "/ws/logs")
                .addInterceptors(new org.springframework.web.socket.server.HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                                  org.springframework.web.socket.WebSocketHandler wsHandler,
                                                  java.util.Map<String, Object> attributes) throws Exception {
                        return true;
                    }

                    @Override
                    public void afterHandshake(org.springframework.web.socket.ServerHttpRequest request,
                                               org.springframework.web.socket.WebSocketHandler wsHandler,
                                               org.springframework.web.socket.session.WebSocketSession session) {
                    }
                });
    }
}
```

- [ ] **Step 2: 创建 LogService**

```java
package com.nexa.flowops.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Service
public class LogService {

    private final String logBasePath = "/data/flowops/logs";

    public LogService() {
        // 确保日志目录存在
        new File(logBasePath).mkdirs();
    }

    public String getLogContent(String filename, long offset, long limit) throws IOException {
        Path filePath = new File(logBasePath, filename).toPath();
        if (!Files.exists(filePath)) {
            return "日志文件不存在";
        }
        try (Stream<String> lines = Files.lines(filePath)) {
            return lines.skip(offset).limit(limit).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    public java.util.List<String> listLogs() {
        File dir = new File(logBasePath);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null) return java.util.Collections.emptyList();
        return java.util.Arrays.stream(files)
                .map(f -> f.getName())
                .sorted(java.util.Collections.reverseOrder())
                .toList();
    }
}
```

- [ ] **Step 3: 创建 LogController**

```java
package com.nexa.flowops.controller;

import com.nexa.flowops.service.LogService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of("code", 200, "data", logService.listLogs());
    }

    @GetMapping("/content")
    public Map<String, Object> content(
            @RequestParam String filename,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "1000") long limit) {
        try {
            String content = logService.getLogContent(filename, offset, limit);
            return Map.of("code", 200, "data", content);
        } catch (Exception e) {
            return Map.of("code", 500, "msg", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/nexa/flowops/config/WebSocketConfig.java src/main/java/com/nexa/flowops/controller/LogController.java src/main/java/com/nexa/flowops/service/LogService.java && git commit -m "feat: 添加日志服务和 WebSocket 支持"
```

---

### Task 10: 前端页面

**Files:**
- Create: `src/main/resources/templates/layout.html`
- Create: `src/main/resources/templates/login.html`
- Create: `src/main/resources/templates/dashboard.html`
- Create: `src/main/resources/templates/service-list.html`
- Create: `src/main/resources/templates/service-edit.html`
- Create: `src/main/resources/static/js/app.js`

- [ ] **Step 1: 创建 layout.html (基础布局)**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>FlowOps - CI/CD 部署平台</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <style>
        body { background: #f5f5f5; }
        .sidebar { background: #2c3e50; min-height: 100vh; color: white; }
        .sidebar a { color: #ecf0f1; text-decoration: none; }
        .sidebar a:hover { background: #34495e; }
    </style>
</head>
<body>
<div class="container-fluid">
    <div class="row">
        <div class="col-md-2 sidebar p-3">
            <h4>FlowOps</h4>
            <hr>
            <ul class="nav flex-column">
                <li class="nav-item"><a class="nav-link" href="/dashboard">仪表盘</a></li>
                <li class="nav-item"><a class="nav-link" href="/services">服务管理</a></li>
                <li class="nav-item"><a class="nav-link" href="/logs">日志查看</a></li>
                <li class="nav-item"><a class="nav-link" href="/users" th:if="${session.role == 'admin'}">用户管理</a></li>
            </ul>
        </div>
        <div class="col-md-10 p-4">
            <div th:replace="~{::content}"></div>
        </div>
    </div>
</div>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"></script>
<script src="/js/app.js"></script>
</body>
</html>
```

- [ ] **Step 2: 创建 login.html**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>登录 - FlowOps</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light d-flex align-items-center justify-content-center" style="height: 100vh;">
<div class="card" style="width: 400px;">
    <div class="card-body">
        <h3 class="card-title text-center mb-4">FlowOps 登录</h3>
        <form id="loginForm">
            <div class="mb-3">
                <label class="form-label">用户名</label>
                <input type="text" class="form-control" name="username" required>
            </div>
            <div class="mb-3">
                <label class="form-label">密码</label>
                <input type="password" class="form-control" name="password" required>
            </div>
            <button type="submit" class="btn btn-primary w-100">登录</button>
        </form>
    </div>
</div>
<script>
document.getElementById('loginForm').onsubmit = async (e) => {
    e.preventDefault();
    const form = new FormData(e.target);
    const res = await fetch('/auth/login', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({username: form.get('username'), password: form.get('password')})
    }).then(r => r.json());
    if (res.code === 200) {
        localStorage.setItem('token', res.data);
        location.href = '/dashboard';
    } else {
        alert(res.msg);
    }
};
</script>
</body>
</html>
```

- [ ] **Step 3: 创建 dashboard.html**

```html
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(~{::content})}">
<th:block id="content">
    <h2>仪表盘</h2>
    <div class="row mt-4">
        <div class="col-md-4">
            <div class="card">
                <div class="card-body">
                    <h5>服务总数</h5>
                    <h2 id="totalServices">-</h2>
                </div>
            </div>
        </div>
        <div class="col-md-4">
            <div class="card">
                <div class="card-body">
                    <h5>运行中</h5>
                    <h2 id="runningServices">-</h2>
                </div>
            </div>
        </div>
        <div class="col-md-4">
            <div class="card">
                <div class="card-body">
                    <h5>部署次数</h5>
                    <h2 id="totalDeploys">-</h2>
                </div>
            </div>
        </div>
    </div>
</th:block>
```

- [ ] **Step 4: 创建 service-list.html**

```html
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(~{::content})}">
<th:block id="content">
    <div class="d-flex justify-content-between align-items-center mb-3">
        <h2>服务管理</h2>
        <button class="btn btn-primary" onclick="location.href='/services/create'">创建服务</button>
    </div>
    <table class="table table-striped">
        <thead>
            <tr>
                <th>ID</th>
                <th>服务名</th>
                <th>端口</th>
                <th>状态</th>
                <th>操作</th>
            </tr>
        </thead>
        <tbody id="serviceTable"></tbody>
    </table>

    <script>
    async function loadServices() {
        const res = await fetch('/api/services/list', {headers: {'Authorization': localStorage.getItem('token')}}).then(r => r.json());
        if (res.code === 200) {
            document.getElementById('serviceTable').innerHTML = res.data.map(s => `
                <tr>
                    <td>${s.id}</td>
                    <td>${s.name}</td>
                    <td>${s.port}</td>
                    <td><span class="badge bg-${s.status === 'running' ? 'success' : 'secondary'}">${s.status}</span></td>
                    <td>
                        <button class="btn btn-sm btn-info" onclick="location.href='/services/${s.id}'">编辑</button>
                        <button class="btn btn-sm btn-success" onclick="deploy(${s.id})">部署</button>
                        <button class="btn btn-sm btn-danger" onclick="stop(${s.id})">停止</button>
                    </td>
                </tr>
            `).join('');
        }
    }
    async function deploy(id) {
        await fetch(`/api/deploy/start/${id}`, {method: 'POST', headers: {'Authorization': localStorage.getItem('token')}}).then(r => r.json()).then(r => {alert(r.msg); loadServices();});
    }
    async function stop(id) {
        await fetch(`/api/deploy/stop/${id}`, {method: 'POST', headers: {'Authorization': localStorage.getItem('token')}}).then(r => r.json()).then(r => {alert(r.msg); loadServices();});
    }
    loadServices();
    </script>
</th:block>
```

- [ ] **Step 5: 创建 service-edit.html**

```html
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(~{::content})}">
<th:block id="content">
    <h2 th:text="${service == null ? '创建服务' : '编辑服务'}"></h2>
    <form id="serviceForm" class="mt-4">
        <input type="hidden" name="id" th:value="${service?.id}">
        <div class="mb-3">
            <label class="form-label">服务名称</label>
            <input type="text" class="form-control" name="name" th:value="${service?.name}" required>
        </div>
        <div class="mb-3">
            <label class="form-label">暴露端口</label>
            <input type="number" class="form-control" name="port" th:value="${service?.port}" required>
        </div>
        <div class="mb-3">
            <label class="form-label">Dockerfile</label>
            <textarea class="form-control" name="dockerfile" rows="10" th:text="${service?.dockerfile}"></textarea>
        </div>
        <div class="mb-3">
            <label class="form-label">docker-compose.yml</label>
            <textarea class="form-control" name="dockerCompose" rows="10" th:text="${service?.dockerCompose}"></textarea>
        </div>
        <button type="submit" class="btn btn-primary">保存</button>
        <button type="button" class="btn btn-secondary" onclick="history.back()">取消</button>
    </form>

    <hr>
    <h4>上传产物</h4>
    <div class="row">
        <div class="col-md-6">
            <div class="mb-3">
                <label class="form-label">上传 JAR 文件</label>
                <input type="file" class="form-control" id="jarFile">
                <button class="btn btn-sm btn-primary mt-2" onclick="uploadJar()">上传</button>
            </div>
        </div>
        <div class="col-md-6">
            <div class="mb-3">
                <label class="form-label">上传前端 dist (zip)</label>
                <input type="file" class="form-control" id="distFile">
                <button class="btn btn-sm btn-primary mt-2" onclick="uploadDist()">上传</button>
            </div>
        </div>
    </div>

    <script>
    document.getElementById('serviceForm').onsubmit = async (e) => {
        e.preventDefault();
        const form = new FormData(e.target);
        const data = {name: form.get('name'), port: form.get('port'), dockerfile: form.get('dockerfile'), dockerCompose: form.get('dockerCompose')};
        const id = form.get('id');
        const url = id ? `/api/services/${id}` : '/api/services/create';
        const method = id ? 'PUT' : 'POST';
        const res = await fetch(url, {method, headers: {'Content-Type': 'application/json', 'Authorization': localStorage.getItem('token')}, body: JSON.stringify(data)}).then(r => r.json());
        if (res.code === 200) { alert(res.msg); location.href = '/services'; }
        else { alert(res.msg); }
    };

    async function uploadJar() {
        const file = document.getElementById('jarFile').files[0];
        if (!file) return;
        const serviceId = document.querySelector('[name=id]').value || 1;
        const form = new FormData();
        form.append('file', file);
        form.append('type', 'jar');
        const res = await fetch(`/api/deploy/upload/${serviceId}`, {method: 'POST', headers: {'Authorization': localStorage.getItem('token')}, body: form}).then(r => r.json());
        alert(res.msg);
    }

    async function uploadDist() {
        const file = document.getElementById('distFile').files[0];
        if (!file) return;
        const serviceId = document.querySelector('[name=id]').value || 1;
        const form = new FormData();
        form.append('file', file);
        const res = await fetch(`/api/deploy/upload-dist/${serviceId}`, {method: 'POST', headers: {'Authorization': localStorage.getItem('token')}, body: form}).then(r => r.json());
        alert(res.msg);
    }
    </script>
</th:block>
```

- [ ] **Step 6: 创建 app.js**

```javascript
// 通用请求封装
async function api(url, options = {}) {
    const token = localStorage.getItem('token');
    const headers = {'Authorization': token};
    if (options.body && !(options.body instanceof FormData)) {
        headers['Content-Type'] = 'application/json';
    }
    const res = await fetch(url, {...options, headers});
    return res.json();
}

// 登出
async function logout() {
    await api('/auth/logout', {method: 'POST'});
    localStorage.removeItem('token');
    location.href = '/login';
}
```

- [ ] **Step 7: 提交**

```bash
git add src/main/resources/templates/*.html src/main/resources/static/js/app.js && git commit -m "feat: 添加前端页面"
```

---

### Task 11: 控制器层添加页面映射

**Files:**
- Modify: `src/main/java/com/nexa/flowops/controller/AuthController.java`
- Create: `src/main/java/com/nexa/flowops/controller/PageController.java`

- [ ] **Step 1: 创建 PageController (页面路由)**

```java
package com.nexa.flowops.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("role", StpUtil.hasRole("admin") ? "admin" : "user");
        return "dashboard";
    }

    @GetMapping("/services")
    public String services() {
        return "service-list";
    }

    @GetMapping("/services/create")
    public String serviceCreate() {
        return "service-edit";
    }

    @GetMapping("/services/{id}")
    public String serviceEdit(@PathVariable Long id, Model model) {
        model.addAttribute("service", null); // 实际应查询
        return "service-edit";
    }

    @GetMapping("/logs")
    public String logs() {
        return "deploy-logs";
    }

    @GetMapping("/users")
    public String users() {
        return "user-list";
    }
}
```

- [ ] **Step 2: 修改 AuthController 添加登录页面**

在 `login` 方法中添加：当已登录时重定向到 dashboard

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/nexa/flowops/controller/PageController.java && git commit -m "feat: 添加页面路由控制器"
```

---

### Task 12: 数据库初始化脚本

**Files:**
- Create: `src/main/resources/sql/init.sql`

- [ ] **Step 1: 创建数据库初始化脚本**

```sql
CREATE DATABASE IF NOT EXISTS flowops DEFAULT CHARACTER SET utf8mb4;

USE flowops;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS deploy_service (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    port INT NOT NULL,
    volume_dir VARCHAR(255),
    dockerfile TEXT,
    docker_compose TEXT,
    status VARCHAR(20) DEFAULT 'stopped',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS deploy_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_id BIGINT NOT NULL,
    status VARCHAR(20),
    log_path VARCHAR(255),
    remark VARCHAR(255),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 默认管理员账户: admin/admin123
INSERT INTO sys_user (username, password, role) VALUES ('admin', 'admin123', 'admin');
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/sql/init.sql && git commit -m "docs: 添加数据库初始化脚本"
```

---

## 实施检查清单

- [ ] Task 1: 项目依赖配置
- [ ] Task 2: 配置文件
- [ ] Task 3: 数据库实体类
- [ ] Task 4: Sa-Token 权限配置
- [ ] Task 5: 认证服务
- [ ] Task 6: 用户管理
- [ ] Task 7: 服务管理
- [ ] Task 8: 文件上传和部署核心逻辑
- [ ] Task 9: 日志服务 (WebSocket)
- [ ] Task 10: 前端页面
- [ ] Task 11: 控制器层添加页面映射
- [ ] Task 12: 数据库初始化脚本

---

## 执行选项

**1. Subagent-Driven (recommended)** - 我会为每个任务启动一个 subagent 来执行，任务间有检查点回顾

**2. Inline Execution** - 在当前 session 中批量执行任务

你选择哪种方式？
