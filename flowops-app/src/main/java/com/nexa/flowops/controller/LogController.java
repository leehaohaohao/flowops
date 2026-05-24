package com.nexa.flowops.controller;

import com.nexa.flowops.common.Result;
import com.nexa.flowops.service.LogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping("/list")
    public Result<List<String>> list() {
        return Result.ok(logService.listLogs());
    }

    @GetMapping("/content")
    public Result<String> content(
            @RequestParam String filename,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "1000") long limit) {
        try {
            String content = logService.getLogContent(filename, offset, limit);
            return Result.ok(content);
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }
}
