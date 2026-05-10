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
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.util.Collections;
import java.util.List;

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

    // ==================== 上传辅助 ====================

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

    // ==================== 部署 ====================

    @SuppressWarnings("unchecked")
    public Result<Void> deploy(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        DeployRecord record = new DeployRecord();
        record.setServiceId(serviceId);
        record.setCreateTime(LocalDateTime.now());

        String logPath = storagePath + "/logs/" + service.getName() + "-" + System.currentTimeMillis() + ".log";

        try {
            // 解析 serviceConfig
            Map<String, Object> config = parseServiceConfig(service.getServiceConfig());
            Map<String, Object> backendConfig = config.containsKey("backend")
                    ? (Map<String, Object>) config.get("backend") : null;
            Map<String, Object> frontendConfig = config.containsKey("frontend")
                    ? (Map<String, Object>) config.get("frontend") : null;
            String serviceType = service.getServiceType();

            String volumeDir = service.getVolumeDir();

            // 根据服务类型生成部署文件
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
                List<Map<String, Object>> proxyRules = (frontendConfig != null && frontendConfig.containsKey("proxyRules"))
                        ? (List<Map<String, Object>>) frontendConfig.get("proxyRules") : Collections.emptyList();
                String customNginx = frontendConfig != null ? (String) frontendConfig.get("customNginxConfig") : null;
                generateNginxConf(volumeDir, proxyTarget, proxyRules, customNginx);
            }
            generateComposeYml(volumeDir, service, serviceType, backendConfig, frontendConfig);

            // 执行 docker compose
            String composePath = volumeDir + "/docker-compose.yml";

            // 先执行 down 清理旧容器
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

            // 重新构建并启动
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

    // ==================== 文件生成 ====================

    private void generateDockerfile(String volumeDir, Map<String, Object> backendConfig) throws IOException {
        String baseImage = getString(backendConfig, "baseImage", "openjdk:17-jdk-slim");
        int containerPort = getInt(backendConfig, "containerPort", 8080);
        String startupCommand = getString(backendConfig, "startupCommand", "java -jar /app/app.jar");

        StringBuilder sb = new StringBuilder();
        sb.append("FROM ").append(baseImage).append("\n");
        sb.append("WORKDIR /app\n");
        sb.append("COPY app.jar /app/app.jar\n");
        sb.append("EXPOSE ").append(containerPort).append("\n");

        // 添加环境变量 ENV 指令
        if (backendConfig != null && backendConfig.containsKey("envVars")) {
            Map<String, String> envVars = (Map<String, String>) backendConfig.get("envVars");
            if (envVars != null) {
                for (Map.Entry<String, String> entry : envVars.entrySet()) {
                    sb.append("ENV ").append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                }
            }
        }

        // ENTRYPOINT 拆分为 JSON 数组格式
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

    private void generateNginxConf(String volumeDir, String proxyTarget, List<Map<String, Object>> proxyRules, String customNginx) throws IOException {
        String content;
        if (customNginx != null && !customNginx.isEmpty()) {
            content = customNginx;
        } else {
            String target = proxyTarget.endsWith("/") ? proxyTarget.substring(0, proxyTarget.length() - 1) : proxyTarget;

            StringBuilder sb = new StringBuilder();
            sb.append("server {\n");
            sb.append("    listen 80;\n");
            sb.append("    server_name localhost;\n");
            sb.append("\n");
            sb.append("    root /usr/share/nginx/html;\n");
            sb.append("    index index.html;\n");
            sb.append("\n");
            sb.append("    # 静态资源缓存\n");
            sb.append("    location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {\n");
            sb.append("        expires 30d;\n");
            sb.append("        add_header Cache-Control \"public, immutable\";\n");
            sb.append("    }\n");

            // 遍历代理规则列表，逐条生成 location 块
            if (proxyRules != null) {
                for (Map<String, Object> rule : proxyRules) {
                    String path = getString(rule, "path", "");
                    if (path.isEmpty()) continue;
                    if (!path.startsWith("/")) path = "/" + path;
                    if (!path.endsWith("/")) path = path + "/";
                    boolean isSse = Boolean.TRUE.equals(rule.get("sse"));

                    sb.append("\n");
                    sb.append("    # 反向代理: ").append(path).append("\n");
                    sb.append("    location ").append(path).append(" {\n");
                    sb.append("        proxy_pass ").append(target).append(path).append(";\n");
                    sb.append("        proxy_set_header Host $host;\n");
                    sb.append("        proxy_set_header X-Real-IP $remote_addr;\n");
                    sb.append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n");
                    sb.append("        proxy_set_header X-Forwarded-Proto $scheme;\n");
                    if (isSse) {
                        sb.append("        proxy_buffering off;\n");
                        sb.append("        proxy_cache off;\n");
                        sb.append("        proxy_read_timeout 86400s;\n");
                        sb.append("        proxy_send_timeout 86400s;\n");
                    }
                    sb.append("    }\n");
                }
            }

            sb.append("\n");
            sb.append("    # SPA 路由\n");
            sb.append("    location / {\n");
            sb.append("        try_files $uri $uri/ /index.html;\n");
            sb.append("    }\n");
            sb.append("}\n");
            content = sb.toString();
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
            int containerPort = getInt(backendConfig, "containerPort", 8080);
            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(hostPort).append(":").append(containerPort).append("\"\n");
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");
        } else if ("frontend".equals(serviceType)) {
            sb.append("  frontend:\n");
            sb.append("    image: ").append(getString(frontendConfig, "baseImage", "nginx:alpine")).append("\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(hostPort).append(":80\"\n");
            sb.append("    volumes:\n");
            sb.append("      - ./dist:/usr/share/nginx/html\n");
            sb.append("      - ./default.conf:/etc/nginx/conf.d/default.conf\n");
            sb.append("    restart: unless-stopped\n");
        } else if ("fullstack".equals(serviceType)) {
            int containerPort = getInt(backendConfig, "containerPort", 8080);

            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    expose:\n");
            sb.append("      - \"").append(containerPort).append("\"\n");
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");

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

    // ==================== 容器操作 ====================

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

    // ==================== 工具方法 ====================

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
