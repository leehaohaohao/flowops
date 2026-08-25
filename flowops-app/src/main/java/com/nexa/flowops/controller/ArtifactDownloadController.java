package com.nexa.flowops.controller;

import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 产物下载端点：把服务 volumeDir 整个目录（配置 + 上传的 jar/dist 产物）流式打成 tar 下发，
 * 供子节点在远程部署 START 前拉取。鉴权走共享密钥（X-Artifact-Token 头或 token 查询参数），
 * 与 Sa-Token 会话无关（子节点无登录态），已在 PermissionInterceptor 中放行。
 */
@RestController
@RequestMapping("/api/deploy/artifact")
public class ArtifactDownloadController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactDownloadController.class);

    private static final String TOKEN_HEADER = "X-Artifact-Token";

    private final DeployServiceMapper serviceMapper;
    private final String artifactToken;

    public ArtifactDownloadController(DeployServiceMapper serviceMapper,
                                      @Value("${nexa.master.artifact-token:}") String artifactToken) {
        this.serviceMapper = serviceMapper;
        this.artifactToken = artifactToken;
    }

    @GetMapping("/{serviceId}")
    public void download(@PathVariable Long serviceId,
                         @RequestParam(value = "token", required = false) String token,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        // 鉴权：配置了共享密钥时必须匹配（头或查询参数二选一）
        if (StringUtils.hasText(artifactToken)) {
            String presented = request.getHeader(TOKEN_HEADER);
            if (!StringUtils.hasText(presented)) {
                presented = token;
            }
            if (!artifactToken.equals(presented)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"message\":\"产物下载令牌无效\"}");
                return;
            }
        }

        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":404,\"message\":\"服务不存在: " + serviceId + "\"}");
            return;
        }

        File volumeDir = new File(service.getVolumeDir());
        if (!volumeDir.exists() || !volumeDir.isDirectory()) {
            log.warn("[{}] 产物目录不存在: {}", service.getName(), service.getVolumeDir());
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":404,\"message\":\"产物目录不存在，请先上传产物\"}");
            return;
        }

        String filename = (StringUtils.hasText(service.getDeployName()) ? service.getDeployName() : "service" + serviceId) + ".tar";
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/x-tar");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        // 产物可能较大，禁止缓存代理缓冲整包
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");

        long start = System.currentTimeMillis();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(response.getOutputStream())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            Path root = volumeDir.toPath();
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> sorted = paths.sorted(Comparator.comparing(Path::toString)).toList();
                for (Path path : sorted) {
                    String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                    if (relative.isEmpty()) {
                        continue; // 根目录自身
                    }
                    File file = path.toFile();
                    if (file.isDirectory()) {
                        TarArchiveEntry entry = new TarArchiveEntry(relative + "/");
                        entry.setModTime(file.lastModified());
                        tar.putArchiveEntry(entry);
                        tar.closeArchiveEntry();
                    } else {
                        TarArchiveEntry entry = new TarArchiveEntry(relative);
                        entry.setSize(file.length());
                        entry.setModTime(file.lastModified());
                        tar.putArchiveEntry(entry);
                        Files.copy(path, tar);
                        tar.closeArchiveEntry();
                    }
                }
            }
            tar.finish();
        } catch (IOException e) {
            log.error("[{}] 产物 tar 打包失败: {}", service.getName(), e.getMessage(), e);
            // 流可能已部分写出，无法再写 JSON 错误，直接抛出让容器处理
            throw e;
        }
        log.info("[{}] 产物下载完成: 目录={}, 耗时={}ms", service.getName(), service.getVolumeDir(),
                System.currentTimeMillis() - start);
    }
}
