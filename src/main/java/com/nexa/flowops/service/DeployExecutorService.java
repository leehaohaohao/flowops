package com.nexa.flowops.service;

import com.nexa.flowops.common.Result;
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
public class DeployExecutorService {

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
            Files.createDirectories(new File(logPath).getParentFile().toPath());
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

            return exitCode == 0 ? Result.ok("部署成功") : Result.fail("部署失败");
        } catch (Exception e) {
            record.setStatus("failed");
            recordMapper.insert(record);
            return Result.fail("部署异常: " + e.getMessage());
        }
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
            ProcessBuilder pb = new ProcessBuilder(
                    "docker-compose", "-f", service.getVolumeDir() + "/docker-compose.yml", "down"
            );
            pb.directory(new File(service.getVolumeDir()));
            pb.start().waitFor();
            service.setStatus("stopped");
            serviceMapper.updateById(service);
            return Result.ok("停止成功");
        } catch (Exception e) {
            return Result.fail("停止失败: " + e.getMessage());
        }
    }

    public Result<Map<String, Object>> getContainerStatus(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        boolean running = dockerUtil.isContainerRunning(service.getName());
        return Result.ok(Map.of("running", running, "status", running ? "running" : "stopped"));
    }
}
