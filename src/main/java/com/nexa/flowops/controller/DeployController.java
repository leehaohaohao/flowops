package com.nexa.flowops.controller;

import com.nexa.flowops.common.Result;
import com.nexa.flowops.service.DeployExecutorService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Map;

@RestController
@RequestMapping("/api/deploy")
public class DeployController {

    private final DeployExecutorService deployService;

    public DeployController(DeployExecutorService deployService) {
        this.deployService = deployService;
    }

    @PostMapping("/upload/{serviceId}")
    public Result<String> upload(
            @PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "jar") String type) {
        String uploadPath = deployService.getUploadPath(serviceId, type);
        try {
            // JAR 类型统一重命名为 app.jar，与默认 docker-compose 模板匹配
            String filename = "jar".equals(type) ? "app.jar" : file.getOriginalFilename();
            File targetFile = new File(uploadPath, filename);
            file.transferTo(targetFile);
            return Result.ok("上传成功", filename);
        } catch (Exception e) {
            return Result.fail("上传失败: " + e.getMessage());
        }
    }

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

    @PostMapping("/start/{serviceId}")
    public Result<Void> start(@PathVariable Long serviceId) {
        return deployService.deploy(serviceId);
    }

    @PostMapping("/stop/{serviceId}")
    public Result<Void> stop(@PathVariable Long serviceId) {
        return deployService.stopContainer(serviceId);
    }

    @GetMapping("/status/{serviceId}")
    public Result<Map<String, Object>> status(@PathVariable Long serviceId) {
        return deployService.getContainerStatus(serviceId);
    }
}
