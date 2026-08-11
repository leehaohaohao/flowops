package com.nexa.flowops.service.deploy;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.ContainerStatusVO;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.generate.ConfigGeneratorChain;
import com.nexa.flowops.service.generate.DeployContext;
import com.nexa.flowops.docker.DockerClient;
import com.nexa.flowops.docker.DockerResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

/**
 * 本机部署执行：通过 DockerClient 在本节点执行 docker compose 生命周期操作
 */
@Component
public class LocalDeployRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDeployRunner.class);

    private final DeployServiceMapper serviceMapper;
    private final DeployRecordMapper recordMapper;
    private final DockerClient dockerClient;
    private final ObjectMapper objectMapper;
    private final ConfigGeneratorChain configGeneratorChain;
    private final DeployLogHelper logHelper;

    public LocalDeployRunner(DeployServiceMapper serviceMapper,
                             DeployRecordMapper recordMapper,
                             DockerClient dockerClient,
                             ObjectMapper objectMapper,
                             ConfigGeneratorChain configGeneratorChain,
                             DeployLogHelper logHelper) {
        this.serviceMapper = serviceMapper;
        this.recordMapper = recordMapper;
        this.dockerClient = dockerClient;
        this.objectMapper = objectMapper;
        this.configGeneratorChain = configGeneratorChain;
        this.logHelper = logHelper;
    }

    public Result<Void> runStart(DeployService service, DeployRecord record) {
        String logPath = record.getLogPath();
        try {
            // 构建上下文并生成配置文件
            DeployContext context = DeployContext.from(service, objectMapper);
            configGeneratorChain.generate(context);

            String volumeDir = service.getVolumeDir();

            // 执行 docker compose
            String composePath = volumeDir + "/docker-compose.yml";

            // 先执行 down 清理旧容器
            log.info("[{}] 执行 docker compose down", service.getName());
            DockerResult downResult = dockerClient.composeDown(new File(volumeDir), composePath);
            if (!downResult.isSuccess()) {
                log.warn("[{}] docker compose down 退出码={}，输出:\n{}", service.getName(), downResult.exitCode(), downResult.output());
            }

            // 重新构建并启动
            log.info("[{}] 执行 docker compose up -d --build", service.getName());
            DockerResult upResult = dockerClient.composeUp(new File(volumeDir), composePath, true);

            logHelper.appendLogContent(logPath, upResult.output());

            if (!upResult.isSuccess()) {
                log.error("[{}] docker compose up 失败，退出码={}，输出:\n{}", service.getName(), upResult.exitCode(), upResult.output());
                record.setStatus("failed");
                service.setStatus("stopped");
                serviceMapper.updateById(service);
                recordMapper.insert(record);
                return Result.fail("部署失败，退出码=" + upResult.exitCode());
            }

            // compose up 成功，轮询容器状态确认服务真正启动
            log.info("[{}] compose up 完成，等待容器启动...", service.getName());
            boolean healthy = waitForContainers(volumeDir, 30);
            if (healthy) {
                log.info("[{}] 部署成功，容器已正常运行", service.getName());
                // 追加容器内服务的运行日志
                String containerLogs = getContainerLogs(volumeDir);
                logHelper.appendLogContent(logPath, "\n\n===== 服务运行日志 =====\n" + containerLogs);
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
                logHelper.appendLogContent(logPath, "\n\n===== 容器启动失败日志 =====\n" + containerLogs);
                return Result.fail("部署失败：容器未能正常启动，请查看部署日志");
            }
        } catch (Exception e) {
            log.error("[{}] 部署异常", service.getName(), e);
            record.setStatus("failed");
            recordMapper.insert(record);
            return Result.fail("部署异常: " + e.getMessage());
        }
    }

    public Result<Void> runStop(DeployService service) {
        try {
            String composePath = service.getVolumeDir() + "/docker-compose.yml";
            log.info("[{}] 执行 docker compose stop", service.getName());
            DockerResult result = dockerClient.composeStop(new File(service.getVolumeDir()), composePath);
            if (!result.isSuccess()) {
                log.warn("[{}] docker compose stop 退出码={}，输出:\n{}", service.getName(), result.exitCode(), result.output());
            }
            service.setStatus("stopped");
            serviceMapper.updateById(service);
            return Result.ok("停止成功");
        } catch (Exception e) {
            log.error("[{}] 停止失败", service.getName(), e);
            return Result.fail("停止失败: " + e.getMessage());
        }
    }

    public Result<Void> runRestart(DeployService service) {
        try {
            String composePath = service.getVolumeDir() + "/docker-compose.yml";
            log.info("[{}] 执行 docker compose restart", service.getName());
            DockerResult result = dockerClient.composeRestart(new File(service.getVolumeDir()), composePath);
            if (!result.isSuccess()) {
                log.warn("[{}] docker compose restart 退出码={}，输出:\n{}", service.getName(), result.exitCode(), result.output());
                service.setStatus("stopped");
                serviceMapper.updateById(service);
                return Result.fail("重启失败，退出码=" + result.exitCode());
            }
            service.setStatus("running");
            serviceMapper.updateById(service);
            return Result.ok("重启成功");
        } catch (Exception e) {
            log.error("[{}] 重启失败", service.getName(), e);
            return Result.fail("重启失败: " + e.getMessage());
        }
    }

    public Result<Void> runRemove(DeployService service) {
        try {
            String composePath = service.getVolumeDir() + "/docker-compose.yml";
            log.info("[{}] 执行 docker compose down", service.getName());
            DockerResult result = dockerClient.composeDown(new File(service.getVolumeDir()), composePath);
            if (!result.isSuccess()) {
                log.warn("[{}] docker compose down 退出码={}，输出:\n{}", service.getName(), result.exitCode(), result.output());
            }
            service.setStatus("stopped");
            serviceMapper.updateById(service);
            return Result.ok("删除成功");
        } catch (Exception e) {
            log.error("[{}] 删除失败", service.getName(), e);
            return Result.fail("删除失败: " + e.getMessage());
        }
    }

    public ContainerStatusVO getStatus(DeployService service) {
        // 注：远程节点的容器状态需通过子节点上报获取，当前仅支持本机容器状态
        boolean running = dockerClient.isContainerRunning(service.getName());
        ContainerStatusVO vo = new ContainerStatusVO();
        vo.setRunning(running);
        vo.setStatus(running ? "running" : "stopped");
        return vo;
    }

    // ==================== 部署健康检查 ====================

    private boolean waitForContainers(String volumeDir, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Thread.sleep(3000);

        while (System.currentTimeMillis() < deadline) {
            DockerResult result = dockerClient.composePs(new File(volumeDir));

            if (result.output() == null || result.output().isBlank()) {
                Thread.sleep(2000);
                continue;
            }

            String[] lines = result.output().trim().split("\n");
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
                DockerResult recheck = dockerClient.composePs(new File(volumeDir));
                if (recheck.output() != null && recheck.output().toLowerCase().contains("restarting")) {
                    return false;
                }
            }

            Thread.sleep(2000);
        }
        return false;
    }

    private String getContainerLogs(String volumeDir) {
        DockerClient.LogsOptions options = DockerClient.LogsOptions.builder().tail(30).build();
        DockerResult result = dockerClient.composeLogs(new File(volumeDir), options);
        return result.isSuccess() ? result.output() : "获取容器日志失败: " + result.output();
    }
}
