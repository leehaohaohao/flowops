package com.nexa.flowops.service;

import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.log.LogSource;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.QueryManager;
import com.nexa.flowops.docker.DockerClient;
import com.nexa.flowops.docker.DockerResult;
import com.nexa.protocol.Query.ContainerLogsRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final LogSource logSource;
    private final DeployServiceMapper serviceMapper;
    private final DockerClient dockerClient;
    private final NodeService nodeService;
    private final QueryManager queryManager;

    public LogService(LogSource logSource, DeployServiceMapper serviceMapper, DockerClient dockerClient,
                      NodeService nodeService, QueryManager queryManager) {
        this.logSource = logSource;
        this.serviceMapper = serviceMapper;
        this.dockerClient = dockerClient;
        this.nodeService = nodeService;
        this.queryManager = queryManager;
    }

    public List<String> listLogFiles(Long serviceId, String type, String date) {
        validateFilename(date);
        return logSource.listFiles(serviceId, type, date);
    }

    public String getLogContent(Long serviceId, String type, String date, String filename,
                                long offset, long limit) {
        validateDate(date);
        validateFilename(filename);
        return logSource.readContent(serviceId, type, date, filename, offset, limit);
    }

    public List<String> listLogDates(Long serviceId, String type) {
        return logSource.listDates(serviceId, type);
    }

    public String getContainerLogs(Long serviceId, int tail, String since, String until, boolean timestamps) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            return "服务不存在";
        }
        // 远程节点：非流式 tail，通过 QueryManager 下发 CONTAINER_LOGS_REQ
        String nodeId = service.getNodeId();
        if (nodeId != null && !nodeId.isBlank()) {
            return getRemoteContainerLogs(service, nodeId, tail, since, until, timestamps);
        }
        // 本机：直接 docker compose logs
        try {
            DockerClient.LogsOptions options = DockerClient.LogsOptions.builder()
                    .tail(tail)
                    .since(since)
                    .until(until)
                    .timestamps(timestamps)
                    .build();

            DockerResult result = dockerClient.composeLogs(new File(service.getVolumeDir()), options);
            if (!result.isSuccess()) {
                log.warn("docker compose logs 退出码: {}, deployName={}, output={}", result.exitCode(), service.getDeployName(),
                        result.output() != null && result.output().length() > 200
                                ? result.output().substring(result.output().length() - 200)
                                : result.output());
            }
            String output = result.output();
            return output == null || output.isEmpty() ? "暂无日志" : output;
        } catch (Exception e) {
            return "获取容器日志失败: " + e.getMessage();
        }
    }

    /**
     * 远程容器日志：解析目标节点（auto 自动调度）后下发查询，同步等待回执
     */
    private String getRemoteContainerLogs(DeployService service, String nodeId, int tail,
                                          String since, String until, boolean timestamps) {
        String resolved = nodeId.trim();
        if ("auto".equalsIgnoreCase(resolved)) {
            Optional<String> leastLoaded = nodeService.selectLeastLoaded();
            if (leastLoaded.isEmpty()) {
                return "无在线子节点，无法获取远程容器日志";
            }
            resolved = leastLoaded.get();
        } else if (!nodeService.isOnline(resolved)) {
            return "目标节点不在线: " + resolved;
        }

        ContainerLogsRequest req = ContainerLogsRequest.newBuilder()
                .setServiceId(String.valueOf(service.getId()))
                .setDeployName(service.getDeployName())
                .setVolumeDir(service.getVolumeDir())
                .setTail(tail)
                .setSince(since == null ? "" : since)
                .setUntil(until == null ? "" : until)
                .setTimestamps(timestamps)
                .build();
        Optional<ContainerLogsResponse> resp =
                queryManager.queryContainerLogs(resolved, req, QueryManager.defaultTimeoutMs());
        if (resp.isEmpty()) {
            return "远程日志查询失败或超时: node=" + resolved;
        }
        ContainerLogsResponse r = resp.get();
        if (r.getError() != null && !r.getError().isEmpty()) {
            return "获取远程容器日志失败: " + r.getError();
        }
        String content = r.getContent();
        return content == null || content.isEmpty() ? "暂无日志" : content;
    }

    private void validateFilename(String filename) {
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("非法文件名");
        }
    }

    private void validateDate(String date) {
        if (date == null || !DATE_PATTERN.matcher(date).matches()) {
            throw new IllegalArgumentException("日期格式不正确，应为 yyyy-MM-dd");
        }
    }
}
