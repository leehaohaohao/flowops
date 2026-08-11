package com.nexa.flowops.service.deploy;

import com.nexa.flowops.entity.DeployService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 部署日志路径解析与内容追加
 */
@Component
public class DeployLogHelper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH-mm-ss");
    private static final long MAX_LOG_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    @Value("${app.logs.path}")
    private String logsBasePath;

    /**
     * 解析日志文件路径：{logsBasePath}/{projectId}/{serviceId}/deploy/{date}/{HH-mm-ss}.log
     */
    public String buildLogPath(DeployService service) {
        String date = LocalDateTime.now().format(DATE_FMT);
        String time = LocalDateTime.now().format(TIME_FMT);
        String logDir = logsBasePath + "/" + service.getProjectId() + "/" + service.getId() + "/deploy/" + date;
        return resolveLogFilePath(logDir, time);
    }

    /**
     * 同一秒内多次部署追加序号，超过 100MB 时拆分文件
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
    public void appendLogContent(String logPath, String content) throws IOException {
        File logFile = new File(logPath);
        Files.createDirectories(logFile.getParentFile().toPath());
        Files.writeString(logFile.toPath(), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
