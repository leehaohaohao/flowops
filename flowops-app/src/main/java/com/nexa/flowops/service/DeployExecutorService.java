package com.nexa.flowops.service;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.ContainerStatusVO;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.generate.ConfigGeneratorChain;
import com.nexa.flowops.service.generate.DeployContext;
import com.nexa.flowops.util.DockerUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipInputStream;

@Service
public class DeployExecutorService {

    private static final Logger log = LoggerFactory.getLogger(DeployExecutorService.class);

    private final DeployServiceMapper serviceMapper;
    private final DeployRecordMapper recordMapper;
    private final DockerUtil dockerUtil;
    private final ObjectMapper objectMapper;
    private final ConfigGeneratorChain configGeneratorChain;

    @Value("${app.logs.path}")
    private String logsBasePath;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH-mm-ss");
    private static final long MAX_LOG_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    public DeployExecutorService(DeployServiceMapper serviceMapper,
                                  DeployRecordMapper recordMapper,
                                  DockerUtil dockerUtil,
                                  ObjectMapper objectMapper,
                                  ConfigGeneratorChain configGeneratorChain) {
        this.serviceMapper = serviceMapper;
        this.recordMapper = recordMapper;
        this.dockerUtil = dockerUtil;
        this.objectMapper = objectMapper;
        this.configGeneratorChain = configGeneratorChain;
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

    public Result<Void> deploy(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        DeployRecord record = new DeployRecord();
        record.setServiceId(serviceId);
        record.setCreateTime(LocalDateTime.now());

        // 日志路径：{logsBasePath}/{projectId}/{serviceId}/deploy/{date}/{HH-mm-ss}.log
        String date = LocalDateTime.now().format(DATE_FMT);
        String time = LocalDateTime.now().format(TIME_FMT);
        String logDir = logsBasePath + "/" + service.getProjectId() + "/" + serviceId + "/deploy/" + date;
        String logPath = resolveLogFilePath(logDir, time);
        record.setLogPath(logPath);

        try {
            // 构建上下文并生成配置文件
            DeployContext context = DeployContext.from(service, objectMapper);
            configGeneratorChain.generate(context);

            String volumeDir = service.getVolumeDir();

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
            appendLogContent(logPath, output);

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
                appendLogContent(logPath, "\n\n===== 服务运行日志 =====\n" + containerLogs);
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
                appendLogContent(logPath, "\n\n===== 容器启动失败日志 =====\n" + containerLogs);
                return Result.fail("部署失败：容器未能正常启动，请查看部署日志");
            }
        } catch (Exception e) {
            log.error("[{}] 部署异常", service.getName(), e);
            record.setStatus("failed");
            recordMapper.insert(record);
            return Result.fail("部署异常: " + e.getMessage());
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

    public Result<ContainerStatusVO> getContainerStatus(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        boolean running = dockerUtil.isContainerRunning(service.getName());
        ContainerStatusVO vo = new ContainerStatusVO();
        vo.setRunning(running);
        vo.setStatus(running ? "running" : "stopped");
        return Result.ok(vo);
    }

    // ==================== 部署健康检查 ====================

    private boolean waitForContainers(String volumeDir, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
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

            String[] lines = output.trim().split("\n");
            boolean allRunning = true;
            boolean anyRestarting = false;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> info = objectMapper.readValue(line, Map.class);
                    String state = String.valueOf(info.getOrDefault("State", "")).toLowerCase();
                    if (state.contains("exited") || state.contains("dead")) {
                        return false;
                    }
                    if (state.contains("restarting")) {
                        anyRestarting = true;
                        allRunning = false;
                    }
                    if (!state.contains("running")) {
                        allRunning = false;
                    }
                } catch (Exception ignored) {
                }
            }

            if (allRunning && !anyRestarting) {
                return true;
            }
            if (anyRestarting) {
                Thread.sleep(3000);
                ProcessBuilder pb2 = dockerUtil.newProcessBuilder(
                        "docker", "compose", "ps", "--format", "json"
                );
                pb2.directory(new File(volumeDir));
                Process proc2 = pb2.start();
                String output2 = readProcessOutput(proc2);
                proc2.waitFor();
                if (output2.toLowerCase().contains("restarting")) {
                    return false;
                }
            }

            Thread.sleep(2000);
        }
        return false;
    }

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

    /**
     * 解析日志文件路径，同一秒内多次部署追加序号，超过 100MB 时拆分文件
     */
    private String resolveLogFilePath(String logDir, String time) {
        String base = logDir + "/" + time;
        String path = base + ".log";
        int seq = 2;
        while (new File(path).exists() && new File(path).length() >= MAX_LOG_FILE_SIZE) {
            path = base + "-" + seq + ".log";
            seq++;
        }
        return path;
    }

    /**
     * 追加内容到日志文件，自动创建目录
     */
    private void appendLogContent(String logPath, String content) throws IOException {
        File logFile = new File(logPath);
        Files.createDirectories(logFile.getParentFile().toPath());
        Files.writeString(logFile.toPath(), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
