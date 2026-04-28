package com.nexa.flowops.controller;

import com.nexa.flowops.service.LogService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of("code", 200, "data", logService.listLogs());
    }

    @GetMapping("/content")
    public Map<String, Object> content(
            @RequestParam String filename,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "1000") long limit) {
        try {
            String content = logService.getLogContent(filename, offset, limit);
            return Map.of("code", 200, "data", content);
        } catch (Exception e) {
            return Map.of("code", 500, "msg", e.getMessage());
        }
    }
}
