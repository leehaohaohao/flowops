package com.nexa.flowops.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultDockerClient implements DockerClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultDockerClient.class);

    // ==================== Docker 基础 ====================

    @Override
    public boolean isDockerAvailable() {
        DockerResult result = executeRaw("docker", "info");
        return result.isSuccess();
    }

    @Override
    public boolean isContainerRunning(String containerName) {
        DockerResult result = executeRaw("docker", "ps", "--filter", "name=" + containerName, "--format", "{{.Names}}");
        return result.isSuccess() && result.output() != null && !result.output().trim().isEmpty();
    }

    @Override
    public String getContainerLog(String containerName, int tail) {
        DockerResult result = executeRaw("docker", "logs", "--tail", String.valueOf(tail), containerName);
        return result.isSuccess() ? result.output() : "获取日志失败: " + result.output();
    }

    // ==================== Docker Compose 生命周期 ====================

    @Override
    public DockerResult composeDown(File workDir, String composeFile) {
        return executeRaw(workDir, "docker", "compose", "-f", composeFile, "down");
    }

    @Override
    public DockerResult composeUp(File workDir, String composeFile, boolean build) {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", composeFile, "up", "-d"));
        if (build) cmd.add("--build");
        return executeRaw(workDir, cmd.toArray(new String[0]));
    }

    @Override
    public DockerResult composeStop(File workDir, String composeFile) {
        return executeRaw(workDir, "docker", "compose", "-f", composeFile, "stop");
    }

    @Override
    public DockerResult composeRestart(File workDir, String composeFile) {
        return executeRaw(workDir, "docker", "compose", "-f", composeFile, "restart");
    }

    @Override
    public DockerResult composePs(File workDir) {
        return executeRaw(workDir, "docker", "compose", "ps", "--format", "{{json .}}");
    }

    // ==================== Docker Compose 日志 ====================

    @Override
    public DockerResult composeLogs(File workDir, LogsOptions options) {
        List<String> cmd = buildLogsCommand(options);
        return executeRaw(workDir, cmd.toArray(new String[0]));
    }

    @Override
    public Process composeLogsFollow(File workDir, LogsOptions options) {
        List<String> cmd = buildLogsCommand(options);
        cmd.add("--follow");
        return startStreaming(workDir, cmd.toArray(new String[0]));
    }

    // ==================== 内部实现 ====================

    private List<String> buildLogsCommand(LogsOptions options) {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "logs", "--no-color"));
        cmd.add("--tail");
        cmd.add(String.valueOf(options.tail()));
        if (options.timestamps()) cmd.add("--timestamps");
        if (options.since() != null && !options.since().isEmpty()) {
            cmd.add("--since");
            cmd.add(options.since());
        }
        if (options.until() != null && !options.until().isEmpty()) {
            cmd.add("--until");
            cmd.add(options.until());
        }
        if (options.service() != null && !options.service().isEmpty()) {
            cmd.add(options.service());
        }
        return cmd;
    }

    private DockerResult executeRaw(String... commands) {
        return executeRaw(null, commands);
    }

    private DockerResult executeRaw(File workDir, String... commands) {
        String[] resolved = resolveDockerPath(commands);
        String cmdStr = String.join(" ", resolved);

        ProcessBuilder pb = new ProcessBuilder(resolved);
        pb.redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir);
        }

        log.info("[Docker] 执行: {}{}", cmdStr, workDir != null ? " (dir=" + workDir.getAbsolutePath() + ")" : "");

        long start = System.currentTimeMillis();
        try {
            Process process = pb.start();
            String output = readProcessOutput(process);
            int exitCode = process.waitFor();
            long elapsed = System.currentTimeMillis() - start;

            log.info("[Docker] 完成: exitCode={}, 耗时={}ms", exitCode, elapsed);

            return new DockerResult(exitCode, output, elapsed, cmdStr);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[Docker] 异常: {}, 耗时={}ms", e.getMessage(), elapsed);
            return new DockerResult(-1, e.getMessage(), elapsed, cmdStr);
        }
    }

    private Process startStreaming(File workDir, String... commands) {
        String[] resolved = resolveDockerPath(commands);
        String cmdStr = String.join(" ", resolved);

        ProcessBuilder pb = new ProcessBuilder(resolved);
        pb.redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir);
        }

        log.info("[Docker] 流式启动: {}{}", cmdStr, workDir != null ? " (dir=" + workDir.getAbsolutePath() + ")" : "");

        try {
            return pb.start();
        } catch (Exception e) {
            throw new RuntimeException("启动 Docker 流式进程失败: " + e.getMessage(), e);
        }
    }

    private String[] resolveDockerPath(String[] commands) {
        String[] resolved = commands.clone();
        if (resolved.length > 0 && "docker".equals(resolved[0])) {
            resolved[0] = findDockerBinary();
        }
        return resolved;
    }

    private String findDockerBinary() {
        String[] candidates = {"/usr/bin/docker", "/usr/local/bin/docker"};
        for (String path : candidates) {
            if (new File(path).canExecute()) {
                return path;
            }
        }
        return "docker";
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
}
