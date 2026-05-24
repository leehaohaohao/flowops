package com.nexa.flowops.service;

import com.nexa.flowops.common.Result;
import com.nexa.flowops.dto.ContainerStatusVO;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.util.DockerUtil;
import com.fasterxml.jackson.core.type.TypeReference;
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

        // 记录条目信息：name -> byte[]（文件内容），目录条目 value 为 null
        record ZipEntryData(String name, byte[] data) {}
        List<ZipEntryData> entries = new ArrayList<>();
        Set<String> topDirs = new LinkedHashSet<>();

        // 单次遍历：读取所有条目到内存，同时检测顶层目录
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    entries.add(new ZipEntryData(name, null));
                    String noSlash = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    if (!noSlash.contains("/")) {
                        topDirs.add(noSlash);
                    }
                } else {
                    entries.add(new ZipEntryData(name, zis.readAllBytes()));
                    if (!name.contains("/")) {
                        topDirs.add(name);
                    }
                }
            }
        }

        // 判断是否需要跳过顶层目录
        String stripPrefix = null;
        if (topDirs.size() == 1) {
            String candidate = topDirs.iterator().next() + "/";
            boolean allUnder = entries.stream().allMatch(e ->
                    e.name.startsWith(candidate) || e.name.equals(candidate.substring(0, candidate.length() - 1)));
            if (allUnder) {
                stripPrefix = candidate;
                log.info("检测到 zip 单层根目录「{}」，自动跳过", topDirs.iterator().next());
            }
        }

        // 写入文件
        for (ZipEntryData zd : entries) {
            String name = zd.name;
            if (stripPrefix != null && name.startsWith(stripPrefix)) {
                name = name.substring(stripPrefix.length());
            }
            if (name.isEmpty()) continue;

            File newFile = new File(targetDir, name);
            if (zd.data == null) {
                newFile.mkdirs();
            } else {
                new File(newFile.getParent()).mkdirs();
                try (FileOutputStream fos = new FileOutputStream(newFile)) {
                    fos.write(zd.data);
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
            if (exitCode != 0) {
                log.error("[{}] docker compose up 失败，退出码={}，输出:\n{}", service.getName(), exitCode, output);
                record.setStatus("failed");
                service.setStatus("stopped");
                serviceMapper.updateById(service);
                recordMapper.insert(record);
                return Result.fail("部署失败，退出码=" + exitCode);
            }

            // compose up 成功，轮询容器状态确认服务真正启动
            log.info("[{}] compose up 完成，等待容器启动...", service.getName());
            boolean healthy = waitForContainers(volumeDir, 30);
            if (healthy) {
                log.info("[{}] 部署成功，容器已正常运行", service.getName());
                // 追加容器内服务的运行日志
                String containerLogs = getContainerLogs(volumeDir);
                Files.writeString(new File(logPath).toPath(),
                        "\n\n===== 服务运行日志 =====\n" + containerLogs,
                        java.nio.file.StandardOpenOption.APPEND);
                record.setStatus("success");
                service.setStatus("running");
                serviceMapper.updateById(service);
                recordMapper.insert(record);
                return Result.ok("部署成功");
            } else {
                // 容器未正常运行，获取日志用于排查
                String containerLogs = getContainerLogs(volumeDir);
                log.error("[{}] 部署失败，容器未正常运行:\n{}", service.getName(), containerLogs);
                record.setStatus("failed");
                service.setStatus("stopped");
                serviceMapper.updateById(service);
                recordMapper.insert(record);
                // 将容器日志追加到部署日志文件
                Files.writeString(new File(logPath).toPath(),
                        "\n\n===== 容器启动失败日志 =====\n" + containerLogs,
                        java.nio.file.StandardOpenOption.APPEND);
                return Result.fail("部署失败：容器未能正常启动，请查看部署日志");
            }
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

        // ENTRYPOINT 拆分为 JSON 数组格式（支持引号参数）
        List<String> cmdParts = splitCommand(startupCommand);
        sb.append("ENTRYPOINT [");
        for (int i = 0; i < cmdParts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(cmdParts.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]\n");

        Files.writeString(new File(volumeDir, "Dockerfile").toPath(), sb.toString());
        log.info("已生成 Dockerfile");
    }

    @SuppressWarnings("unchecked")
    private void generateNginxConf(String volumeDir, String proxyTarget, List<Map<String, Object>> proxyRules, String customNginx) throws IOException {
        String content;
        if (customNginx != null && !customNginx.isEmpty()) {
            content = customNginx;
        } else {
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

                    sb.append("\n");
                    sb.append("    # 反向代理: ").append(path).append("\n");
                    sb.append("    location ").append(path).append(" {\n");

                    // 遍历该规则下的所有指令
                    List<Map<String, String>> directives = (List<Map<String, String>>) rule.get("directives");
                    if (directives != null) {
                        for (Map<String, String> d : directives) {
                            String name = d.get("name");
                            String value = d.get("value");
                            if (name != null && !name.isEmpty()) {
                                sb.append("        ").append(name);
                                if (value != null && !value.isEmpty()) {
                                    sb.append(" ").append(value);
                                }
                                sb.append(";\n");
                            }
                        }
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
        List<Map<String, Integer>> extraPorts = parseExtraPorts(service.getExtraPorts());
        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");

        if ("backend".equals(serviceType)) {
            int containerPort = getInt(backendConfig, "containerPort", 8080);
            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(hostPort).append(":").append(containerPort).append("\"\n");
            appendExtraPorts(sb, extraPorts);
            appendVolumes(sb, backendConfig);
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");
        } else if ("frontend".equals(serviceType)) {
            sb.append("  frontend:\n");
            sb.append("    image: ").append(getString(frontendConfig, "baseImage", "nginx:alpine")).append("\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(hostPort).append(":80\"\n");
            appendExtraPorts(sb, extraPorts);
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
            if (!extraPorts.isEmpty()) {
                sb.append("    ports:\n");
                appendExtraPorts(sb, extraPorts);
            }
            appendVolumes(sb, backendConfig);
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

    private List<Map<String, Integer>> parseExtraPorts(String extraPortsJson) {
        if (extraPortsJson == null || extraPortsJson.isBlank()) return List.of();
        try {
            return new ObjectMapper().readValue(extraPortsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 extraPorts 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void appendExtraPorts(StringBuilder sb, List<Map<String, Integer>> extraPorts) {
        for (Map<String, Integer> ep : extraPorts) {
            Integer hp = ep.get("hostPort");
            Integer cp = ep.get("containerPort");
            if (hp != null && cp != null) {
                sb.append("      - \"").append(hp).append(":").append(cp).append("\"\n");
            }
        }
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

    // 添加 volume 挂载（数据持久化）
    @SuppressWarnings("unchecked")
    private void appendVolumes(StringBuilder sb, Map<String, Object> config) {
        if (config == null || !config.containsKey("dataMount")) return;
        Map<String, Object> dataMount = (Map<String, Object>) config.get("dataMount");
        if (dataMount == null) return;
        String containerPath = getString(dataMount, "containerPath", "");
        if (containerPath.isEmpty()) return;
        String hostDir = getString(dataMount, "hostDir", "./data");
        sb.append("    volumes:\n");
        sb.append("      - ").append(hostDir).append(":").append(containerPath).append("\n");
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

    public Result<ContainerStatusVO> getContainerStatus(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        boolean running = dockerUtil.isContainerRunning(service.getName());
        ContainerStatusVO vo = new ContainerStatusVO();
        vo.setRunning(running);
        vo.setStatus(running ? "running" : "stopped");
        return Result.ok(vo);
    }

    // ==================== 部署健康检查 ====================

    /**
     * 轮询容器状态，等待所有容器进入稳定运行状态
     * @return true 表示所有容器正常运行，false 表示有容器异常（重启中或已退出）
     */
    private boolean waitForContainers(String volumeDir, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        // 给容器几秒初始启动时间
        Thread.sleep(3000);

        while (System.currentTimeMillis() < deadline) {
            ProcessBuilder pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "ps", "--format", "{{json .}}"
            );
            pb.directory(new File(volumeDir));
            Process proc = pb.start();
            String output = readProcessOutput(proc);
            proc.waitFor();

            if (output.isBlank()) {
                Thread.sleep(2000);
                continue;
            }

            // 解析每行 JSON（每个容器一行）
            String[] lines = output.trim().split("\n");
            boolean allRunning = true;
            boolean anyRestarting = false;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> info = objectMapper.readValue(line, Map.class);
                    String state = String.valueOf(info.getOrDefault("State", "")).toLowerCase();
                    // RestartCount 不一定在所有版本都有，优先看 State
                    if (state.contains("exited") || state.contains("dead")) {
                        return false; // 容器已退出，直接判定失败
                    }
                    if (state.contains("restarting")) {
                        anyRestarting = true;
                        allRunning = false;
                    }
                    if (!state.contains("running")) {
                        allRunning = false;
                    }
                } catch (Exception ignored) {
                    // JSON 解析失败，跳过
                }
            }

            if (allRunning && !anyRestarting) {
                return true;
            }
            if (anyRestarting) {
                // 有容器在重启，再等几秒看能否稳定
                Thread.sleep(3000);
                // 再检查一次
                ProcessBuilder pb2 = dockerUtil.newProcessBuilder(
                        "docker", "compose", "ps", "--format", "json"
                );
                pb2.directory(new File(volumeDir));
                Process proc2 = pb2.start();
                String output2 = readProcessOutput(proc2);
                proc2.waitFor();
                // 如果仍然在重启，判定失败
                if (output2.toLowerCase().contains("restarting")) {
                    return false;
                }
            }

            Thread.sleep(2000);
        }
        return false;
    }

    /**
     * 获取所有容器的最近日志，用于排查启动失败原因
     */
    private String getContainerLogs(String volumeDir) {
        try {
            ProcessBuilder pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "logs", "--tail", "30"
            );
            pb.directory(new File(volumeDir));
            Process proc = pb.start();
            String output = readProcessOutput(proc);
            proc.waitFor();
            return output;
        } catch (Exception e) {
            return "获取容器日志失败: " + e.getMessage();
        }
    }

    /**
     * 获取指定服务的容器运行日志（REST API 用）
     */
    public String getContainerLogs(Long serviceId, int tail) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            return "服务不存在";
        }
        try {
            ProcessBuilder pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "logs", "--tail", String.valueOf(tail), "--no-color", service.getName()
            );
            pb.directory(new File(service.getVolumeDir()));
            Process proc = pb.start();
            String output = readProcessOutput(proc);
            proc.waitFor();
            return output.isEmpty() ? "暂无日志" : output;
        } catch (Exception e) {
            return "获取容器日志失败: " + e.getMessage();
        }
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

    /**
     * Shell 风格命令拆分，支持单引号和双引号包裹的参数
     * 例: java -jar app.jar --spring.profiles.active="prod dev" -> [java, -jar, app.jar, --spring.profiles.active=prod dev]
     */
    static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'') {
                    inSingleQuote = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }
}
