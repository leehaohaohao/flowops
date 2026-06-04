package com.nexa.flowops.service.log;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
@Service
public class LocalDockerLogSource implements LogSource{
    @Value("${app.logs.path}")
    private String logPath;
    @Override
    public List<String> listFiles(Long serviceId, String type, String date) {

        return List.of();
    }

    @Override
    public String readContent(Long serviceId, String type, String filename, long offset, long limit) {

        return "";
    }

    @Override
    public List<String> listDates(Long serviceId, String type) {
        return List.of();
    }
}
