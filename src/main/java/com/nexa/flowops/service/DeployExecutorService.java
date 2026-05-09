package com.nexa.flowops.service;

import com.nexa.flowops.common.Result;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.util.DockerUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

@Service
public class DeployExecutorService {

    private static final Logger log = LoggerFactory.getLogger(DeployExecutorService.class);

    private final DeployServiceMapper serviceMapper;
    private final DeployRecordMapper recordMapper;
    private final DockerUtil dockerUtil;
    private final String storagePath = "/data/flowops/services";

    public DeployExecutorService(DeployServiceMapper serviceMapper, DeployRecordMapper recordMapper, DockerUtil dockerUtil) {
        this.serviceMapper = serviceMapper;
        this.recordMapper = recordMapper;
        this.dockerUtil = dockerUtil;
    }

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

    public Result<Void> deploy(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        DeployRecord record = new DeployRecord();
        record.setServiceId(serviceId);
        record.setCreateTime(LocalDateTime.now());

        String logPath = storagePath + "/logs/" + service.getName() + "-" + System.currentTimeMillis() + ".log";

        try {
            // 生成 docker-compose.yml
            String composeContent = service.getDockerCompose();
            if (composeContent == null || composeContent.isEmpty()) {
                log.info("[{}] dockerCompose 字段为空，使用默认模板", service.getName());
                composeContent = generateDefaultCompose(service);
            } else {
                log.info("[{}] 使用数据库存储的 dockerCompose 内容:\n{}", service.getName(), composeContent);
            }
            File composeFile = new File(service.getVolumeDir(), "docker-compose.yml");
            Files.writeString(composeFile.toPath(), composeContent);

            String composePath = composeFile.getAbsolutePath();

            // down
            log.info("[{}] 执行 docker compose down", service.getName());
            ProcessBuilder pb = dockerUtil.newProcessBuilder(
                    "docker", "compose", "-f", composePath, "down"
            );
            pb.directory(new File(service.getVolumeDir()));
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
            pb.directory(new File(service.getVolumeDir()));
            Process process = pb.start();

            // 同时写入部署日志文件
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

    private String generateDefaultCompose(DeployService service) {
        String port = String.valueOf(service.getPort());
        return "version: '3'\n" +
                "services:\n" +
                "  app:\n" +
                "    build: .\n" +
                "    ports:\n" +
                "      - \"" + port + ":8080\"\n" +
                "    volumes:\n" +
                "      - ./app.jar:/app/app.jar\n" +
                "    restart: unless-stopped\n";
    }

    public Result<Void> stopContainer(Long serviceId) {
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
            return Result.ok("停止成功");
        } catch (Exception e) {
            log.error("[{}] 停止失败", service.getName(), e);
            return Result.fail("停止失败: " + e.getMessage());
        }
    }

    public Result<Map<String, Object>> getContainerStatus(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        boolean running = dockerUtil.isContainerRunning(service.getName());
        return Result.ok(Map.of("running", running, "status", running ? "running" : "stopped"));
    }
}
