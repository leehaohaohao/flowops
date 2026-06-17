package com.nexa.flowops.service;

import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.log.LogSource;
import com.nexa.flowops.util.DockerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final LogSource logSource;
    private final DeployServiceMapper serviceMapper;
    private final DockerUtil dockerUtil;

    public LogService(LogSource logSource, DeployServiceMapper serviceMapper, DockerUtil dockerUtil) {
        this.logSource = logSource;
        this.serviceMapper = serviceMapper;
        this.dockerUtil = dockerUtil;
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
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    "docker", "compose",
                    "--project-directory", service.getVolumeDir(),
                    "logs",
                    "--tail", String.valueOf(tail)
            ));
            if (timestamps) cmd.add("--timestamps");
            if (since != null && !since.isEmpty()) { cmd.add("--since"); cmd.add(since); }
            if (until != null && !until.isEmpty()) { cmd.add("--until"); cmd.add(until); }

            ProcessBuilder pb = dockerUtil.newProcessBuilder(cmd.toArray(new String[0]));
            log.info("执行命令: {}", String.join(" ", cmd));
            Process proc = pb.start();
            String output = readProcessOutput(proc);
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                log.warn("docker compose logs 退出码: {}, deployName={}, output={}", exitCode, service.getDeployName(),
                        output.length() > 200 ? output.substring(output.length() - 200) : output);
            }
            return output.isEmpty() ? "暂无日志" : output;
        } catch (Exception e) {
            return "获取容器日志失败: " + e.getMessage();
        }
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
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
