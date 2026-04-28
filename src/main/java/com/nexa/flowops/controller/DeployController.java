package com.nexa.flowops.controller;

import com.nexa.flowops.service.DeployService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/deploy")
public class DeployController {

    private final DeployService deployService;

    public DeployController(DeployService deployService) {
        this.deployService = deployService;
    }

    @PostMapping("/upload/{serviceId}")
    public Map<String, Object> upload(
            @PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "jar") String type) {
        String filename = file.getOriginalFilename();
        String uploadPath = deployService.getUploadPath(serviceId, type);
        try {
            File targetFile = new File(uploadPath, filename);
            file.transferTo(targetFile);
            return Map.of("code", 200, "msg", "上传成功", "filename", filename);
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/upload-dist/{serviceId}")
    public Map<String, Object> uploadDist(
            @PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file) {
        String uploadPath = deployService.getUploadPath(serviceId, "dist");
        try {
            // 解压 dist 到目标目录
            deployService.extractDist(file, uploadPath);
            return Map.of("code", 200, "msg", "前端文件上传成功");
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/start/{serviceId}")
    public Map<String, Object> start(@PathVariable Long serviceId) {
        return deployService.deploy(serviceId);
    }

    @PostMapping("/stop/{serviceId}")
    public Map<String, Object> stop(@PathVariable Long serviceId) {
        return deployService.stopContainer(serviceId);
    }

    @GetMapping("/status/{serviceId}")
    public Map<String, Object> status(@PathVariable Long serviceId) {
        return deployService.getContainerStatus(serviceId);
    }
}
