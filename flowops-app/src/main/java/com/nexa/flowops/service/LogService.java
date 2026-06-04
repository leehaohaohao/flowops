package com.nexa.flowops.service;

import com.nexa.flowops.service.log.LogSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class LogService {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final LogSource logSource;

    public LogService(LogSource logSource) {
        this.logSource = logSource;
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
