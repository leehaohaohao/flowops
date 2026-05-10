package com.nexa.flowops.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Service
public class LogService {

    private final String logBasePath = "/data/flowops/services/logs";

    public LogService() {
        // 确保日志目录存在
        new File(logBasePath).mkdirs();
    }

    public String getLogContent(String filename, long offset, long limit) throws IOException {
        Path filePath = new File(logBasePath, filename).toPath();
        if (!Files.exists(filePath)) {
            return "日志文件不存在";
        }
        try (Stream<String> lines = Files.lines(filePath)) {
            return lines.skip(offset).limit(limit).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    public java.util.List<String> listLogs() {
        File dir = new File(logBasePath);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null) return java.util.Collections.emptyList();
        return java.util.Arrays.stream(files)
                .map(f -> f.getName())
                .sorted(java.util.Collections.reverseOrder())
                .toList();
    }
}
