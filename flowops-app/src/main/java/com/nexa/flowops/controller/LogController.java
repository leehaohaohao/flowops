package com.nexa.flowops.controller;

import com.nexa.flowops.common.anno.RequirePermission;
import com.nexa.flowops.common.base.Result;
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

    @RequirePermission(value = "VIEW", projectId = "serviceId")
    @GetMapping("/list")
    public Result<List<String>> list(
            @RequestParam Long serviceId,
            @RequestParam String type,
            @RequestParam String date) {
        return Result.ok(logService.listLogFiles(serviceId, type, date));
    }

    @RequirePermission(value = "VIEW", projectId = "serviceId")
    @GetMapping("/content")
    public Result<String> content(
            @RequestParam Long serviceId,
            @RequestParam String type,
            @RequestParam String date,
            @RequestParam String filename,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "4096") long limit) {
        try {
            String data = logService.getLogContent(serviceId, type, date, filename, offset, limit);
            return Result.ok(null, data);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @RequirePermission(value = "VIEW", projectId = "serviceId")
    @GetMapping("/dates")
    public Result<List<String>> dates(
            @RequestParam Long serviceId,
            @RequestParam String type) {
        return Result.ok(logService.listLogDates(serviceId, type));
    }

    @RequirePermission("VIEW")
    @GetMapping("/container/{serviceId}")
    public Result<String> containerLogs(
            @PathVariable Long serviceId,
            @RequestParam(defaultValue = "500") int tail,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "false") boolean timestamps) {
        String logs = logService.getContainerLogs(serviceId, tail, since, until, timestamps);
        return Result.ok(null, logs);
    }
}
