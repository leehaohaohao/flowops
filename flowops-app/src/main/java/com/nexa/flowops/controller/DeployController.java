package com.nexa.flowops.controller;

import com.nexa.flowops.common.RequirePermission;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.dto.ContainerStatusVO;
import com.nexa.flowops.service.DeployExecutorService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@RestController
@RequestMapping("/api/deploy")
public class DeployController {

    private final DeployExecutorService deployService;

    public DeployController(DeployExecutorService deployService) {
        this.deployService = deployService;
    }

    @RequirePermission("UPLOAD")
    @PostMapping("/upload/{serviceId}")
    public Result<String> upload(
            @PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "jar") String type) {
        String uploadPath = deployService.getUploadPath(serviceId, type);
        try {
            String filename = "jar".equals(type) ? "app.jar" : file.getOriginalFilename();
            File targetFile = new File(uploadPath, filename);
            file.transferTo(targetFile);
            return Result.ok("上传成功", filename);
        } catch (Exception e) {
            return Result.fail("上传失败: " + e.getMessage());
        }
    }

    @RequirePermission("UPLOAD")
    @PostMapping("/upload-dist/{serviceId}")
    public Result<Void> uploadDist(
            @PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file) {
        String uploadPath = deployService.getUploadPath(serviceId, "dist");
        try {
            deployService.extractDist(file, uploadPath);
            return Result.ok("前端文件上传成功");
        } catch (Exception e) {
            return Result.fail("上传失败: " + e.getMessage());
        }
    }

    @RequirePermission("DEPLOY")
    @PostMapping("/start/{serviceId}")
    public Result<Void> start(@PathVariable Long serviceId) {
        return deployService.deploy(serviceId);
    }

    @RequirePermission("STOP")
    @PostMapping("/stop/{serviceId}")
    public Result<Void> stop(@PathVariable Long serviceId) {
        return deployService.stopContainer(serviceId);
    }

    @RequirePermission("DEPLOY")
    @PostMapping("/restart/{serviceId}")
    public Result<Void> restart(@PathVariable Long serviceId) {
        return deployService.restartContainer(serviceId);
    }

    @RequirePermission("DELETE")
    @PostMapping("/remove/{serviceId}")
    public Result<Void> remove(@PathVariable Long serviceId) {
        return deployService.removeContainer(serviceId);
    }

    @RequirePermission("VIEW")
    @GetMapping("/status/{serviceId}")
    public Result<ContainerStatusVO> status(@PathVariable Long serviceId) {
        return deployService.getContainerStatus(serviceId);
    }

    @RequirePermission("VIEW")
    @GetMapping("/logs/{serviceId}")
    public Result<String> containerLogs(
            @PathVariable Long serviceId,
            @RequestParam(defaultValue = "500") int tail) {
        String logs = deployService.getContainerLogs(serviceId, tail);
        return Result.ok(logs);
    }
}
