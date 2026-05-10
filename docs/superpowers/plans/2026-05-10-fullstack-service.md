# Fullstack Service Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support three service types (backend-only, frontend-only, fullstack) in FlowOps, allowing a single service to manage both frontend and backend deployment via docker-compose.

**Architecture:** Add `service_type` and `service_config` (JSON) columns to `deploy_service`. The deploy engine reads the service type and config to dynamically generate Dockerfile(s), nginx config, and docker-compose.yml. The frontend form dynamically shows/hides sections based on selected service type.

**Tech Stack:** Spring Boot 3.3, MyBatis-Plus, Thymeleaf, Bootstrap 5, CodeMirror 5, Docker Compose V2

---

### Task 1: Database Schema — Add service_type and service_config columns

**Files:**
- Modify: `src/main/resources/sql/init.sql`

- [ ] **Step 1: Update init.sql to add new columns**

Replace the `deploy_service` table definition with:

```sql
CREATE TABLE IF NOT EXISTS deploy_service (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    port INT NOT NULL,
    volume_dir VARCHAR(255),
    service_type VARCHAR(20) NOT NULL DEFAULT 'backend',
    service_config TEXT,
    status VARCHAR(20) DEFAULT 'stopped',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

Changes: removed `dockerfile` and `docker_compose` columns, added `service_type` and `service_config`.

- [ ] **Step 2: Apply schema change to the running MySQL database**

Run:
```sql
ALTER TABLE deploy_service
    ADD COLUMN service_type VARCHAR(20) NOT NULL DEFAULT 'backend' AFTER volume_dir,
    ADD COLUMN service_config TEXT AFTER service_type;
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/sql/init.sql
git commit -m "feat: add service_type and service_config columns to deploy_service"
```

---

### Task 2: Entity — Add new fields to DeployService.java

**Files:**
- Modify: `src/main/java/com/nexa/flowops/entity/DeployService.java`

- [ ] **Step 1: Update DeployService entity**

Replace the entity with:

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
    private Integer port;          // 暴露端口
    private String volumeDir;      // 挂载目录
    private String serviceType;    // 服务类型：backend / frontend / fullstack
    private String serviceConfig;  // 结构化配置 JSON
    private String status;         // running, stopped
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

Changes: replaced `dockerfile` and `dockerCompose` with `serviceType` and `serviceConfig`.

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/nexa/flowops/entity/DeployService.java
git commit -m "feat: update DeployService entity with serviceType and serviceConfig"
```

---

### Task 3: Service Management — Update createService/updateService

**Files:**
- Modify: `src/main/java/com/nexa/flowops/service/ServiceMgmtService.java`

- [ ] **Step 1: Update ServiceMgmtService**

Replace the file contents with:

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
        service.setPort(Integer.parseInt(String.valueOf(params.get("port"))));
        service.setServiceType((String) params.get("serviceType"));
        service.setServiceConfig((String) params.get("serviceConfig"));
        service.setVolumeDir(storagePath + "/" + service.getName());
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
            service.setPort(Integer.parseInt(String.valueOf(params.get("port"))));
        }
        if (params.containsKey("serviceType")) {
            service.setServiceType((String) params.get("serviceType"));
        }
        if (params.containsKey("serviceConfig")) {
            service.setServiceConfig((String) params.get("serviceConfig"));
        }
        serviceMapper.updateById(service);
    }

    public void deleteService(Long id) {
        serviceMapper.deleteById(id);
    }
}
```

Changes: `createService` now reads `serviceType` and `serviceConfig` from params instead of `dockerfile`/`dockerCompose`. `updateService` handles the new fields.

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/nexa/flowops/service/ServiceMgmtService.java
git commit -m "feat: update ServiceMgmtService for serviceType and serviceConfig"
```

---

### Task 4: Deploy Engine — Refactor deploy() to generate files from serviceConfig

**Files:**
- Modify: `src/main/java/com/nexa/flowops/service/DeployExecutorService.java`

This is the core task. The `deploy()` method must read `serviceType` and `serviceConfig` from the DB, then generate the appropriate Dockerfile(s), nginx config, and docker-compose.yml.

- [ ] **Step 1: Replace DeployExecutorService.java entirely**

```java
package com.nexa.flowops.service;

import com.nexa.flowops.common.Result;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.util.DockerUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipInputStream;

@Service
public class DeployExecutorService {

    private static final Logger log = LoggerFactory.getLogger(DeployExecutorService.class);

    private final DeployServiceMapper serviceMapper;
    private final DeployRecordMapper recordMapper;
    private final DockerUtil dockerUtil;
    private final ObjectMapper objectMapper;
    private final String storagePath = "/data/flowops/services";

    public DeployExecutorService(DeployServiceMapper serviceMapper,
                                  DeployRecordMapper recordMapper,
                                  DockerUtil dockerUtil,
                                  ObjectMapper objectMapper) {
        this.serviceMapper = serviceMapper;
        this.recordMapper = recordMapper;
        this.dockerUtil = dockerUtil;
        this.objectMapper = objectMapper;
    }

    // ==================== Upload helpers ====================

    public String getUploadPath(Long serviceId, String type) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            throw new RuntimeException("服务不存在: " + serviceId);
        }
        String path = service.getVolumeDir();
        if ("dist".equals(type)) {
            path = path + "/dist";
        }
        return path;
    }

    public void extractDist(MultipartFile file, String targetDir) throws IOException {
        File dir = new File(targetDir);
        if (dir.exists()) {
            deleteDirectory(dir);
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

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    // ==================== Deploy ====================

    @SuppressWarnings("unchecked")
    public Result<Void> deploy(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        DeployRecord record = new DeployRecord();
        record.setServiceId(serviceId);
        record.setCreateTime(LocalDateTime.now());

        String logPath = storagePath + "/logs/" + service.getName() + "-" + System.currentTimeMillis() + ".log";

        try {
            // Parse serviceConfig
            Map<String, Object> config = parseServiceConfig(service.getServiceConfig());
            Map<String, Object> backendConfig = config.containsKey("backend")
                    ? (Map<String, Object>) config.get("backend") : null;
            Map<String, Object> frontendConfig = config.containsKey("frontend")
                    ? (Map<String, Object>) config.get("frontend") : null;
            String serviceType = service.getServiceType();

            String volumeDir = service.getVolumeDir();

            // Generate deploy files based on service type
            if ("backend".equals(serviceType) || "fullstack".equals(serviceType)) {
                generateDockerfile(volumeDir, backendConfig);
            }
            if ("frontend".equals(serviceType) || "fullstack".equals(serviceType)) {
                String proxyTarget;
                if ("fullstack".equals(serviceType)) {
                    int containerPort = getInt(backendConfig, "containerPort", 8080);
                    proxyTarget = "http://backend:" + containerPort;
                } else {
                    proxyTarget = frontendConfig != null && frontendConfig.containsKey("backendUrl")
                            ? (String) frontendConfig.get("backendUrl") : "http://localhost:8080";
                }
                String customNginx = frontendConfig != null ? (String) frontendConfig.get("customNginxConfig") : null;
                generateNginxConf(volumeDir, proxyTarget, customNginx);
            }
            generateComposeYml(volumeDir, service, serviceType, backendConfig, frontendConfig);

            // Execute docker compose
            String composePath = volumeDir + "/docker-compose.yml";

            // down
            log.info("[{}] 执行 docker compose down", service.getName());
            ProcessBuilder pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "-f", composePath, "down"
            );
            pb.directory(new File(volumeDir));
            Process downProc = pb.start();
            String downOutput = readProcessOutput(downProc);
            int downCode = downProc.waitFor();
            if (downCode != 0) {
                log.warn("[{}] docker compose down 退出码={}，输出:\n{}", service.getName(), downCode, downOutput);
            }

            // up -d --build
            log.info("[{}] 执行 docker compose up -d --build", service.getName());
            pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "-f", composePath, "up", "-d", "--build"
            );
            pb.directory(new File(volumeDir));
            Process process = pb.start();

            String output = readProcessOutput(process);
            Files.createDirectories(new File(logPath).getParentFile().toPath());
            Files.writeString(new File(logPath).toPath(), output);

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("[{}] 部署成功", service.getName());
                record.setStatus("success");
                service.setStatus("running");
            } else {
                log.error("[{}] 部署失败，退出码={}，输出:\n{}", service.getName(), exitCode, output);
                record.setStatus("failed");
                service.setStatus("stopped");
            }
            serviceMapper.updateById(service);
            recordMapper.insert(record);

            return exitCode == 0 ? Result.ok("部署成功") : Result.fail("部署失败，退出码=" + exitCode);
        } catch (Exception e) {
            log.error("[{}] 部署异常", service.getName(), e);
            record.setStatus("failed");
            recordMapper.insert(record);
            return Result.fail("部署异常: " + e.getMessage());
        }
    }

    // ==================== File generators ====================

    private void generateDockerfile(String volumeDir, Map<String, Object> backendConfig) throws IOException {
        String baseImage = getString(backendConfig, "baseImage", "openjdk:17-jdk-slim");
        int containerPort = getInt(backendConfig, "containerPort", 8080);
        String startupCommand = getString(backendConfig, "startupCommand", "java -jar /app/app.jar");

        StringBuilder sb = new StringBuilder();
        sb.append("FROM ").append(baseImage).append("\n");
        sb.append("WORKDIR /app\n");
        sb.append("COPY app.jar /app/app.jar\n");
        sb.append("EXPOSE ").append(containerPort).append("\n");

        // Add ENV lines from envVars
        if (backendConfig != null && backendConfig.containsKey("envVars")) {
            Map<String, String> envVars = (Map<String, String>) backendConfig.get("envVars");
            if (envVars != null) {
                for (Map.Entry<String, String> entry : envVars.entrySet()) {
                    sb.append("ENV ").append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                }
            }
        }

        // ENTRYPOINT as JSON array
        String[] cmdParts = startupCommand.split("\\s+");
        sb.append("ENTRYPOINT [");
        for (int i = 0; i < cmdParts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(cmdParts[i]).append("\"");
        }
        sb.append("]\n");

        Files.writeString(new File(volumeDir, "Dockerfile").toPath(), sb.toString());
        log.info("已生成 Dockerfile");
    }

    private void generateNginxConf(String volumeDir, String proxyTarget, String customNginx) throws IOException {
        String content;
        if (customNginx != null && !customNginx.isEmpty()) {
            content = customNginx;
        } else {
            // Ensure proxyTarget does not end with /
            String target = proxyTarget.endsWith("/") ? proxyTarget.substring(0, proxyTarget.length() - 1) : proxyTarget;
            content = "server {\n" +
                    "    listen 80;\n" +
                    "    server_name localhost;\n" +
                    "\n" +
                    "    root /usr/share/nginx/html;\n" +
                    "    index index.html;\n" +
                    "\n" +
                    "    # Static assets caching\n" +
                    "    location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {\n" +
                    "        expires 30d;\n" +
                    "        add_header Cache-Control \"public, immutable\";\n" +
                    "    }\n" +
                    "\n" +
                    "    # API proxy\n" +
                    "    location /api/ {\n" +
                    "        proxy_pass " + target + "/api/;\n" +
                    "        proxy_set_header Host $host;\n" +
                    "        proxy_set_header X-Real-IP $remote_addr;\n" +
                    "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n" +
                    "        proxy_set_header X-Forwarded-Proto $scheme;\n" +
                    "    }\n" +
                    "\n" +
                    "    # SSE proxy\n" +
                    "    location /api/sse/ {\n" +
                    "        proxy_pass " + target + "/api/sse/;\n" +
                    "        proxy_set_header Host $host;\n" +
                    "        proxy_set_header X-Real-IP $remote_addr;\n" +
                    "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n" +
                    "        proxy_set_header X-Forwarded-Proto $scheme;\n" +
                    "        proxy_buffering off;\n" +
                    "        proxy_cache off;\n" +
                    "        proxy_read_timeout 86400s;\n" +
                    "        proxy_send_timeout 86400s;\n" +
                    "    }\n" +
                    "\n" +
                    "    # SPA routing\n" +
                    "    location / {\n" +
                    "        try_files $uri $uri/ /index.html;\n" +
                    "    }\n" +
                    "}\n";
        }
        Files.writeString(new File(volumeDir, "default.conf").toPath(), content);
        log.info("已生成 default.conf");
    }

    @SuppressWarnings("unchecked")
    private void generateComposeYml(String volumeDir, DeployService service, String serviceType,
                                     Map<String, Object> backendConfig,
                                     Map<String, Object> frontendConfig) throws IOException {
        int hostPort = service.getPort();
        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");

        if ("backend".equals(serviceType)) {
            // Backend only
            int containerPort = getInt(backendConfig, "containerPort", 8080);
            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(hostPort).append(":").append(containerPort).append("\"\n");
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");
        } else if ("frontend".equals(serviceType)) {
            // Frontend only
            sb.append("  frontend:\n");
            sb.append("    image: ").append(getString(frontendConfig, "baseImage", "nginx:alpine")).append("\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(hostPort).append(":80\"\n");
            sb.append("    volumes:\n");
            sb.append("      - ./dist:/usr/share/nginx/html\n");
            sb.append("      - ./default.conf:/etc/nginx/conf.d/default.conf\n");
            sb.append("    restart: unless-stopped\n");
        } else if ("fullstack".equals(serviceType)) {
            // Fullstack: backend + frontend
            int containerPort = getInt(backendConfig, "containerPort", 8080);

            // Backend service (internal only)
            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    expose:\n");
            sb.append("      - \"").append(containerPort).append("\"\n");
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");

            // Frontend service (public)
            sb.append("  frontend:\n");
            sb.append("    image: ").append(getString(frontendConfig, "baseImage", "nginx:alpine")).append("\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(hostPort).append(":80\"\n");
            sb.append("    volumes:\n");
            sb.append("      - ./dist:/usr/share/nginx/html\n");
            sb.append("      - ./default.conf:/etc/nginx/conf.d/default.conf\n");
            sb.append("    depends_on:\n");
            sb.append("      - backend\n");
            sb.append("    restart: unless-stopped\n");
        }

        Files.writeString(new File(volumeDir, "docker-compose.yml").toPath(), sb.toString());
        log.info("[{}] 已生成 docker-compose.yml (type={})", service.getName(), serviceType);
    }

    @SuppressWarnings("unchecked")
    private void appendEnvironment(StringBuilder sb, Map<String, Object> config) {
        if (config == null || !config.containsKey("envVars")) return;
        Map<String, String> envVars = (Map<String, String>) config.get("envVars");
        if (envVars == null || envVars.isEmpty()) return;
        sb.append("    environment:\n");
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            sb.append("      - ").append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
    }

    // ==================== Container operations ====================

    public Result<Void> stopContainer(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        try {
            String composePath = service.getVolumeDir() + "/docker-compose.yml";
            log.info("[{}] 执行 docker compose stop", service.getName());
            ProcessBuilder pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "-f", composePath, "stop"
            );
            pb.directory(new File(service.getVolumeDir()));
            Process process = pb.start();
            String output = readProcessOutput(process);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("[{}] docker compose stop 退出码={}，输出:\n{}", service.getName(), exitCode, output);
            }
            service.setStatus("stopped");
            serviceMapper.updateById(service);
            return Result.ok("停止成功");
        } catch (Exception e) {
            log.error("[{}] 停止失败", service.getName(), e);
            return Result.fail("停止失败: " + e.getMessage());
        }
    }

    public Result<Void> restartContainer(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        try {
            String composePath = service.getVolumeDir() + "/docker-compose.yml";
            log.info("[{}] 执行 docker compose restart", service.getName());
            ProcessBuilder pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "-f", composePath, "restart"
            );
            pb.directory(new File(service.getVolumeDir()));
            Process process = pb.start();
            String output = readProcessOutput(process);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("[{}] docker compose restart 退出码={}，输出:\n{}", service.getName(), exitCode, output);
                service.setStatus("stopped");
                serviceMapper.updateById(service);
                return Result.fail("重启失败，退出码=" + exitCode);
            }
            service.setStatus("running");
            serviceMapper.updateById(service);
            return Result.ok("重启成功");
        } catch (Exception e) {
            log.error("[{}] 重启失败", service.getName(), e);
            return Result.fail("重启失败: " + e.getMessage());
        }
    }

    public Result<Void> removeContainer(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        try {
            String composePath = service.getVolumeDir() + "/docker-compose.yml";
            log.info("[{}] 执行 docker compose down", service.getName());
            ProcessBuilder pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "-f", composePath, "down"
            );
            pb.directory(new File(service.getVolumeDir()));
            Process process = pb.start();
            String output = readProcessOutput(process);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("[{}] docker compose down 退出码={}，输出:\n{}", service.getName(), exitCode, output);
            }
            service.setStatus("stopped");
            serviceMapper.updateById(service);
            return Result.ok("删除成功");
        } catch (Exception e) {
            log.error("[{}] 删除失败", service.getName(), e);
            return Result.fail("删除失败: " + e.getMessage());
        }
    }

    public Result<Map<String, Object>> getContainerStatus(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        boolean running = dockerUtil.isContainerRunning(service.getName());
        return Result.ok(Map.of("running", running, "status", running ? "running" : "stopped"));
    }

    // ==================== Helpers ====================

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseServiceConfig(String json) {
        if (json == null || json.isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("解析 serviceConfig 失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return defaultValue; }
    }
}
```

Key changes from original:
- Injected `ObjectMapper` for JSON parsing
- `deploy()` reads `serviceType` and `serviceConfig`, generates files accordingly
- Removed `generateDefaultCompose()`, added `generateDockerfile()`, `generateNginxConf()`, `generateComposeYml()`
- `extractDist()` now properly deletes existing dir recursively before extracting
- Container operations (`stopContainer`, `restartContainer`, `removeContainer`) unchanged — they already use compose commands

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/nexa/flowops/service/DeployExecutorService.java
git commit -m "feat: refactor deploy engine for fullstack service support"
```

---

### Task 5: Frontend — Rewrite service-edit.html

**Files:**
- Modify: `src/main/resources/templates/service-edit.html`

- [ ] **Step 1: Rewrite service-edit.html**

Replace the entire file with:

```html
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(~{::content})}">
<th:block th:fragment="content">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/codemirror@5.65.16/lib/codemirror.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/codemirror@5.65.16/theme/monokai.min.css">
    <style>
        .CodeMirror { height: 250px; border: 1px solid #ced4da; border-radius: 0.375rem; font-size: 14px; }
        .config-section { background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 0.375rem; padding: 1.25rem; margin-bottom: 1rem; }
        .config-section h5 { margin-bottom: 1rem; }
        .env-row { display: flex; gap: 0.5rem; margin-bottom: 0.5rem; align-items: center; }
        .env-row input { flex: 1; }
    </style>

    <h2 th:text="${service == null ? '创建服务' : '编辑服务'}"></h2>

    <form id="serviceForm" class="mt-4">
        <input type="hidden" name="id" th:value="${service?.id}">

        <!-- Basic info -->
        <div class="row mb-3">
            <div class="col-md-4">
                <label class="form-label">服务类型 <span class="text-danger">*</span></label>
                <select class="form-select" name="serviceType" id="serviceType" required>
                    <option value="backend">纯后端</option>
                    <option value="frontend">纯前端</option>
                    <option value="fullstack">前后端一体</option>
                </select>
            </div>
            <div class="col-md-4">
                <label class="form-label">服务名称 <span class="text-danger">*</span></label>
                <input type="text" class="form-control" name="name" th:value="${service?.name}" required>
            </div>
            <div class="col-md-4">
                <label class="form-label">暴露端口 <span class="text-danger">*</span></label>
                <input type="number" class="form-control" name="port" th:value="${service?.port}" required>
                <small class="text-muted">纯后端:后端对外端口 | 纯前端/一体:前端对外端口</small>
            </div>
        </div>

        <!-- Backend config section -->
        <div class="config-section" id="backendSection">
            <h5>后端配置</h5>
            <div class="row mb-3">
                <div class="col-md-6">
                    <label class="form-label">基础镜像</label>
                    <input type="text" class="form-control" id="backendBaseImage" value="openjdk:17-jdk-slim">
                </div>
                <div class="col-md-3">
                    <label class="form-label">容器端口</label>
                    <input type="number" class="form-control" id="backendContainerPort" value="8090">
                </div>
                <div class="col-md-3">
                    <label class="form-label">启动命令</label>
                    <input type="text" class="form-control" id="backendStartupCommand" value="java -jar /app/app.jar">
                </div>
            </div>
            <div class="mb-3">
                <label class="form-label">
                    环境变量
                    <button type="button" class="btn btn-sm btn-outline-primary ms-2" onclick="addEnvVar()">+ 添加</button>
                </label>
                <div id="envVarsContainer"></div>
            </div>
        </div>

        <!-- Frontend config section -->
        <div class="config-section" id="frontendSection">
            <h5>前端配置</h5>
            <div class="row mb-3">
                <div class="col-md-6">
                    <label class="form-label">基础镜像</label>
                    <input type="text" class="form-control" id="frontendBaseImage" value="nginx:alpine">
                </div>
                <div class="col-md-6" id="backendUrlGroup">
                    <label class="form-label">后端地址</label>
                    <input type="text" class="form-control" id="frontendBackendUrl" placeholder="http://121.40.154.188:8090">
                    <small class="text-muted">仅纯前端模式需要填写，前后端一体模式自动使用 Docker 内部通信</small>
                </div>
            </div>

            <!-- Advanced nginx config -->
            <div class="mt-3">
                <button class="btn btn-sm btn-outline-secondary" type="button"
                        data-bs-toggle="collapse" data-bs-target="#nginxConfigCollapse">
                    高级：自定义 Nginx 配置
                </button>
                <div class="collapse mt-2" id="nginxConfigCollapse">
                    <div class="form-check mb-2">
                        <input class="form-check-input" type="checkbox" id="useCustomNginx">
                        <label class="form-check-label" for="useCustomNginx">使用自定义配置</label>
                    </div>
                    <div id="nginxConfigEditor"></div>
                </div>
            </div>
        </div>

        <!-- Buttons -->
        <button type="submit" class="btn btn-primary">保存</button>
        <button type="button" class="btn btn-info" onclick="previewConfig()">预览配置</button>
        <button type="button" class="btn btn-secondary" onclick="history.back()">取消</button>
    </form>

    <!-- Upload section (edit mode only) -->
    <div th:if="${service != null}" class="mt-4">
        <hr>
        <h4>上传产物</h4>
        <div class="row">
            <div class="col-md-6" id="uploadJarSection">
                <div class="mb-3">
                    <label class="form-label">上传 JAR 文件</label>
                    <input type="file" class="form-control" id="jarFile">
                    <button class="btn btn-sm btn-primary mt-2" onclick="uploadJar()">上传</button>
                </div>
            </div>
            <div class="col-md-6" id="uploadDistSection">
                <div class="mb-3">
                    <label class="form-label">上传前端 dist (zip)</label>
                    <input type="file" class="form-control" id="distFile">
                    <button class="btn btn-sm btn-primary mt-2" onclick="uploadDist()">上传</button>
                </div>
            </div>
        </div>
    </div>

    <!-- Preview Modal -->
    <div class="modal fade" id="previewModal" tabindex="-1">
        <div class="modal-dialog modal-xl">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">部署配置预览</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                </div>
                <div class="modal-body">
                    <div id="previewDockerfileSection" style="display:none">
                        <h6>Dockerfile</h6>
                        <pre class="bg-dark text-light p-3 rounded"><code id="previewDockerfile"></code></pre>
                    </div>
                    <div id="previewNginxSection" style="display:none">
                        <h6>default.conf</h6>
                        <pre class="bg-dark text-light p-3 rounded"><code id="previewNginx"></code></pre>
                    </div>
                    <h6>docker-compose.yml</h6>
                    <pre class="bg-dark text-light p-3 rounded"><code id="previewCompose"></code></pre>
                </div>
            </div>
        </div>
    </div>

    <script src="https://cdn.jsdelivr.net/npm/codemirror@5.65.16/lib/codemirror.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/codemirror@5.65.16/mode/yaml/yaml.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/codemirror@5.65.16/addon/edit/matchbrackets.min.js"></script>
    <script>
    let nginxCM = null;
    const cmOpts = { theme: 'monokai', lineNumbers: true, tabSize: 2, indentWithTabs: false, matchBrackets: true, lineWrapping: true };

    // ==================== Service type switching ====================
    const serviceTypeSelect = document.getElementById('serviceType');
    serviceTypeSelect.addEventListener('change', updateSections);

    function updateSections() {
        const type = serviceTypeSelect.value;
        const backendSection = document.getElementById('backendSection');
        const frontendSection = document.getElementById('frontendSection');
        const backendUrlGroup = document.getElementById('backendUrlGroup');
        const uploadJarSection = document.getElementById('uploadJarSection');
        const uploadDistSection = document.getElementById('uploadDistSection');

        // Show/hide backend config
        const showBackend = type === 'backend' || type === 'fullstack';
        backendSection.style.display = showBackend ? '' : 'none';

        // Show/hide frontend config
        const showFrontend = type === 'frontend' || type === 'fullstack';
        frontendSection.style.display = showFrontend ? '' : 'none';

        // Backend URL field only for frontend-only mode
        backendUrlGroup.style.display = type === 'frontend' ? '' : 'none';

        // Upload sections
        if (uploadJarSection) uploadJarSection.style.display = showBackend ? '' : 'none';
        if (uploadDistSection) uploadDistSection.style.display = showFrontend ? '' : 'none';
    }

    // ==================== Env vars ====================
    function addEnvVar(key, value) {
        const container = document.getElementById('envVarsContainer');
        const row = document.createElement('div');
        row.className = 'env-row';
        row.innerHTML = `
            <input type="text" class="form-control form-control-sm env-key" placeholder="KEY" value="${key || ''}">
            <span>=</span>
            <input type="text" class="form-control form-control-sm env-value" placeholder="VALUE" value="${value || ''}">
            <button type="button" class="btn btn-sm btn-outline-danger" onclick="this.parentElement.remove()">×</button>
        `;
        container.appendChild(row);
    }

    function getEnvVars() {
        const vars = {};
        document.querySelectorAll('#envVarsContainer .env-row').forEach(row => {
            const key = row.querySelector('.env-key').value.trim();
            const value = row.querySelector('.env-value').value.trim();
            if (key) vars[key] = value;
        });
        return vars;
    }

    // ==================== Collect config ====================
    function collectServiceConfig() {
        const type = serviceTypeSelect.value;
        const config = {};

        if (type === 'backend' || type === 'fullstack') {
            config.backend = {
                baseImage: document.getElementById('backendBaseImage').value.trim(),
                containerPort: parseInt(document.getElementById('backendContainerPort').value) || 8080,
                startupCommand: document.getElementById('backendStartupCommand').value.trim(),
                envVars: getEnvVars()
            };
        }

        if (type === 'frontend' || type === 'fullstack') {
            const customNginxChecked = document.getElementById('useCustomNginx').checked;
            config.frontend = {
                baseImage: document.getElementById('frontendBaseImage').value.trim(),
                backendUrl: document.getElementById('frontendBackendUrl').value.trim() || null,
                // Only store customNginxConfig when checkbox is checked; null means use auto-generated
                customNginxConfig: (customNginxChecked && nginxCM && nginxCM.getValue().trim()) ? nginxCM.getValue() : null
            };
        }

        return config;
    }

    // ==================== Preview ====================
    function previewConfig() {
        const type = serviceTypeSelect.value;
        const config = collectServiceConfig();
        const port = parseInt(document.querySelector('[name=port]').value) || 8080;

        let dockerfile = '';
        let nginxConf = '';
        let compose = 'services:\n';

        // Generate preview based on type
        if (type === 'backend' || type === 'fullstack') {
            const b = config.backend;
            dockerfile = `FROM ${b.baseImage}\nWORKDIR /app\nCOPY app.jar /app/app.jar\nEXPOSE ${b.containerPort}`;
            if (b.envVars) {
                for (const [k, v] of Object.entries(b.envVars)) {
                    dockerfile += `\nENV ${k}=${v}`;
                }
            }
            const cmdParts = b.startupCommand.split(/\s+/);
            dockerfile += `\nENTRYPOINT [${cmdParts.map(p => '"' + p + '"').join(', ')}]`;
        }

        if (type === 'frontend' || type === 'fullstack') {
            const proxyTarget = type === 'fullstack'
                ? `http://backend:${config.backend.containerPort}`
                : (config.frontend.backendUrl || 'http://localhost:8080');
            nginxConf = generateNginxConfPreview(proxyTarget);
        }

        if (type === 'backend') {
            compose += `  backend:\n    build: .\n    ports:\n      - "${port}:${config.backend.containerPort}"\n`;
            if (config.backend.envVars && Object.keys(config.backend.envVars).length) {
                compose += `    environment:\n`;
                for (const [k, v] of Object.entries(config.backend.envVars)) {
                    compose += `      - ${k}=${v}\n`;
                }
            }
            compose += `    restart: unless-stopped\n`;
        } else if (type === 'frontend') {
            compose += `  frontend:\n    image: ${config.frontend.baseImage}\n    ports:\n      - "${port}:80"\n    volumes:\n      - ./dist:/usr/share/nginx/html\n      - ./default.conf:/etc/nginx/conf.d/default.conf\n    restart: unless-stopped\n`;
        } else if (type === 'fullstack') {
            compose += `  backend:\n    build: .\n    expose:\n      - "${config.backend.containerPort}"\n`;
            if (config.backend.envVars && Object.keys(config.backend.envVars).length) {
                compose += `    environment:\n`;
                for (const [k, v] of Object.entries(config.backend.envVars)) {
                    compose += `      - ${k}=${v}\n`;
                }
            }
            compose += `    restart: unless-stopped\n`;
            compose += `  frontend:\n    image: ${config.frontend.baseImage}\n    ports:\n      - "${port}:80"\n    volumes:\n      - ./dist:/usr/share/nginx/html\n      - ./default.conf:/etc/nginx/conf.d/default.conf\n    depends_on:\n      - backend\n    restart: unless-stopped\n`;
        }

        // Show/hide sections
        document.getElementById('previewDockerfileSection').style.display = dockerfile ? '' : 'none';
        document.getElementById('previewNginxSection').style.display = nginxConf ? '' : 'none';
        document.getElementById('previewDockerfile').textContent = dockerfile;
        document.getElementById('previewNginx').textContent = nginxConf;
        document.getElementById('previewCompose').textContent = compose;

        new bootstrap.Modal(document.getElementById('previewModal')).show();
    }

    function generateNginxConfPreview(proxyTarget) {
        const t = proxyTarget.endsWith('/') ? proxyTarget.slice(0, -1) : proxyTarget;
        return `server {
    listen 80;
    server_name localhost;
    root /usr/share/nginx/html;
    index index.html;

    location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    location /api/ {
        proxy_pass ${t}/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /api/sse/ {
        proxy_pass ${t}/api/sse/;
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
}`;
    }

    // ==================== Form submit ====================
    document.getElementById('serviceForm').onsubmit = async (e) => {
        e.preventDefault();
        const form = new FormData(e.target);
        const config = collectServiceConfig();
        const data = {
            name: form.get('name'),
            port: form.get('port'),
            serviceType: form.get('serviceType'),
            serviceConfig: JSON.stringify(config)
        };
        const id = form.get('id');
        const url = id ? `/api/services/${id}` : '/api/services/create';
        const method = id ? 'PUT' : 'POST';
        try {
            const res = await api(url, {method, body: JSON.stringify(data)});
            if (res.code === 200) { showToast(res.msg, 'success'); setTimeout(() => location.href = '/services', 1000); }
            else { showToast(res.msg || '保存失败', 'danger'); }
        } catch (e) { /* api() handles token expiry redirect */ }
    };

    // ==================== Upload ====================
    async function uploadJar() {
        const file = document.getElementById('jarFile').files[0];
        if (!file) return;
        const serviceId = document.querySelector('[name=id]').value;
        if (!serviceId) { showToast('请先保存服务', 'warning'); return; }
        const form = new FormData();
        form.append('file', file);
        form.append('type', 'jar');
        try {
            const res = await api(`/api/deploy/upload/${serviceId}`, {method: 'POST', body: form});
            showToast(res.msg, res.code === 200 ? 'success' : 'danger');
        } catch (e) { /* api() handles token expiry redirect */ }
    }

    async function uploadDist() {
        const file = document.getElementById('distFile').files[0];
        if (!file) return;
        const serviceId = document.querySelector('[name=id]').value;
        if (!serviceId) { showToast('请先保存服务', 'warning'); return; }
        const form = new FormData();
        form.append('file', file);
        try {
            const res = await api(`/api/deploy/upload-dist/${serviceId}`, {method: 'POST', body: form});
            showToast(res.msg, res.code === 200 ? 'success' : 'danger');
        } catch (e) { /* api() handles token expiry redirect */ }
    }

    // ==================== Init: load existing data ====================
    (function initFromServer() {
        const serviceData = /*[[${service}]]*/ null;
        if (!serviceData) {
            updateSections();
            return;
        }

        // Set service type
        if (serviceData.serviceType) {
            serviceTypeSelect.value = serviceData.serviceType;
        }

        // Parse and fill serviceConfig
        let config = {};
        try { config = JSON.parse(serviceData.serviceConfig || '{}'); } catch(e) {}

        if (config.backend) {
            document.getElementById('backendBaseImage').value = config.backend.baseImage || 'openjdk:17-jdk-slim';
            document.getElementById('backendContainerPort').value = config.backend.containerPort || 8090;
            document.getElementById('backendStartupCommand').value = config.backend.startupCommand || 'java -jar /app/app.jar';
            if (config.backend.envVars) {
                for (const [k, v] of Object.entries(config.backend.envVars)) {
                    addEnvVar(k, v);
                }
            }
        }

        if (config.frontend) {
            document.getElementById('frontendBaseImage').value = config.frontend.baseImage || 'nginx:alpine';
            document.getElementById('frontendBackendUrl').value = config.frontend.backendUrl || '';
            if (config.frontend.customNginxConfig) {
                document.getElementById('useCustomNginx').checked = true;
            }
        }

        updateSections();

        // Init CodeMirror for nginx config
        nginxCM = CodeMirror(document.getElementById('nginxConfigEditor'), {
            ...cmOpts, mode: 'yaml',
            value: config.frontend?.customNginxConfig || ''
        });
    })();
    </script>
</th:block>
```

Key features:
- Service type dropdown with dynamic show/hide of backend/frontend sections
- Backend config: base image, container port, startup command, env vars (dynamic add/remove)
- Frontend config: base image, backend URL (frontend-only mode), custom nginx config (collapsible)
- Preview modal showing generated Dockerfile, default.conf, docker-compose.yml
- Upload sections shown/hidden based on service type
- On edit: loads existing serviceConfig JSON back into form fields

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/service-edit.html
git commit -m "feat: rewrite service-edit form with service type support"
```

---

### Task 6: Frontend — Update service-list.html to show service type

**Files:**
- Modify: `src/main/resources/templates/service-list.html`

- [ ] **Step 1: Add service type column to the table**

In `service-list.html`, update the table header and the JS rendering to include a "类型" column.

Replace the `<thead>` section:
```html
<thead>
    <tr>
        <th>ID</th>
        <th>服务名</th>
        <th>类型</th>
        <th>端口</th>
        <th>状态</th>
        <th>操作</th>
    </tr>
</thead>
```

Update the JavaScript rendering inside `loadServices()`:
```javascript
const typeLabels = {backend: '纯后端', frontend: '纯前端', fullstack: '前后端一体'};
// ... in the map callback, add:
<td>${typeLabels[s.serviceType] || s.serviceType || '后端'}</td>
```

Full replacement of the `<script>` block:

```html
<script>
const typeLabels = {backend: '纯后端', frontend: '纯前端', fullstack: '前后端一体'};

async function loadServices() {
    const res = await api('/api/services/list');
    if (res.code === 200) {
        document.getElementById('serviceTable').innerHTML = res.data.map(s => `
            <tr>
                <td>${s.id}</td>
                <td>${s.name}</td>
                <td><span class="badge bg-info">${typeLabels[s.serviceType] || s.serviceType || '后端'}</span></td>
                <td>${s.port}</td>
                <td><span class="badge bg-${s.status === 'running' ? 'success' : 'secondary'}">${s.status}</span></td>
                <td>
                    <button class="btn btn-sm btn-info" onclick="location.href='/services/${s.id}'">编辑</button>
                    <button class="btn btn-sm btn-success" onclick="deploy(${s.id})">部署</button>
                    <button class="btn btn-sm btn-warning" onclick="restart(${s.id})">重启</button>
                    <button class="btn btn-sm btn-secondary" onclick="stop(${s.id})">停止</button>
                    <button class="btn btn-sm btn-danger" onclick="remove(${s.id})">删除</button>
                </td>
            </tr>
        `).join('');
    }
}
async function deploy(id) {
    const r = await api(`/api/deploy/start/${id}`, {method: 'POST'});
    showToast(r.msg, r.code === 200 ? 'success' : 'danger');
    loadServices();
}
async function stop(id) {
    const r = await api(`/api/deploy/stop/${id}`, {method: 'POST'});
    showToast(r.msg, r.code === 200 ? 'success' : 'danger');
    loadServices();
}
async function restart(id) {
    const r = await api(`/api/deploy/restart/${id}`, {method: 'POST'});
    showToast(r.msg, r.code === 200 ? 'success' : 'danger');
    loadServices();
}
async function remove(id) {
    if (!confirm('确认删除该容器？')) return;
    const r = await api(`/api/deploy/remove/${id}`, {method: 'POST'});
    showToast(r.msg, r.code === 200 ? 'success' : 'danger');
    loadServices();
}
loadServices();
</script>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/service-list.html
git commit -m "feat: add service type column to service list"
```

---

### Task 7: End-to-end verification

- [ ] **Step 1: Start the application**

Run: `./mvnw spring-boot:run`
Expected: Application starts on port 8080 without errors.

- [ ] **Step 2: Login and create a backend-only service**

1. Open `http://localhost:8080`, login as `admin/admin123`
2. Go to 服务管理 → 创建服务
3. Select 纯后端, fill name `test-backend`, port `8081`, base image `openjdk:17-jdk-slim`, container port `8090`
4. Add env var: `SPRING_PROFILES_ACTIVE=prod`
5. Click 预览配置 → verify Dockerfile and docker-compose.yml look correct
6. Save → verify service appears in list with type badge "纯后端"

- [ ] **Step 3: Create a frontend-only service**

1. Create service → 纯前端, name `test-frontend`, port `8082`
2. Fill backend URL `http://host.docker.internal:8081`
3. Preview → verify Nginx config and compose
4. Save → verify in list

- [ ] **Step 4: Create a fullstack service**

1. Create service → 前后端一体, name `test-fullstack`, port `8083`
2. Fill backend: image `openjdk:17-jdk-slim`, port `8090`, command `java -jar /app/app.jar`
3. Fill frontend: image `nginx:alpine`
4. Preview → verify compose has both `backend` (expose only) and `frontend` (ports mapped)
5. Save → verify in list

- [ ] **Step 5: Verify edit and type switching**

1. Edit `test-backend` → change type to 前后端一体 → frontend config section appears
2. Save → verify type updated in list
3. Edit back to 纯后端 → frontend section hides

- [ ] **Step 6: Commit any fixes found during testing**

```bash
git add -A
git commit -m "fix: adjustments from end-to-end testing"
```
