package com.nexa.flowops.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Component
public class DockerUtil {

    private static final Logger log = LoggerFactory.getLogger(DockerUtil.class);

    /**
     * 获取 docker 可执行文件的绝对路径。
     * Spring Boot 以 systemd 服务运行时 PATH 可能不包含 /usr/bin，
     * 因此优先使用绝对路径，找不到时回退到 "docker" 让系统自行查找。
     */
    public String dockerPath() {
        // 常见安装路径
        String[] candidates = {"/usr/bin/docker", "/usr/local/bin/docker"};
        for (String path : candidates) {
            if (new java.io.File(path).canExecute()) {
                return path;
            }
        }
        return "docker";
    }

    /**
     * 创建带有默认配置的 ProcessBuilder。
     * 自动设置 docker 绝对路径并重定向 stderr 到 stdout 以便一起读取。
     */
    public ProcessBuilder newProcessBuilder(String... commands) {
        // 将 commands[0]（"docker"）替换为绝对路径
        String[] resolved = commands.clone();
        resolved[0] = dockerPath();
        ProcessBuilder pb = new ProcessBuilder(resolved);
        pb.redirectErrorStream(true);  // stderr 合并到 stdout
        return pb;
    }

    public boolean isContainerRunning(String containerName) {
        try {
            ProcessBuilder pb = newProcessBuilder("docker", "ps", "--filter", "name=" + containerName, "--format", "{{.Names}}");
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                return line != null && !line.isEmpty();
            }
        } catch (Exception e) {
            log.warn("检查容器状态失败: {}", e.getMessage());
            return false;
        }
    }

    public String getContainerLog(String containerName, int tail) {
        try {
            ProcessBuilder pb = newProcessBuilder("docker", "logs", "--tail", String.valueOf(tail), containerName);
            Process process = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            process.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "获取日志失败: " + e.getMessage();
        }
    }
}
