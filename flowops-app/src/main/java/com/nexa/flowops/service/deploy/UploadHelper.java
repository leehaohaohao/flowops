package com.nexa.flowops.service.deploy;

import com.nexa.flowops.common.util.DigestUtil;
import com.nexa.flowops.entity.DeployArtifact;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.artifact.ArtifactRegistry;
import com.nexa.flowops.service.artifact.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipInputStream;

/**
 * 部署产物上传与解压
 */
@Component
public class UploadHelper {

    private static final Logger log = LoggerFactory.getLogger(UploadHelper.class);

    private final DeployServiceMapper serviceMapper;
    private final ArtifactRegistry artifactRegistry;
    private final ArtifactStore artifactStore;

    public UploadHelper(DeployServiceMapper serviceMapper,
                        ArtifactRegistry artifactRegistry,
                        ArtifactStore artifactStore) {
        this.serviceMapper = serviceMapper;
        this.artifactRegistry = artifactRegistry;
        this.artifactStore = artifactStore;
    }

    public String getUploadPath(Long serviceId, String type) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            throw new RuntimeException("服务不存在: " + serviceId);
        }
        String path = service.getVolumeDir();
        if ("dist".equals(type)) {
            path = path + "/dist";
        }
        return path;
    }

    public void extractDist(MultipartFile file, String targetDir) throws IOException {
        File dir = new File(targetDir);
        if (dir.exists()) {
            deleteDirectory(dir);
        }
        dir.mkdirs();

        // 记录条目信息：name -> byte[]（文件内容），目录条目 value 为 null
        record ZipEntryData(String name, byte[] data) {}
        List<ZipEntryData> entries = new ArrayList<>();
        Set<String> topDirs = new LinkedHashSet<>();

        // 单次遍历：读取所有条目到内存，同时检测顶层目录
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    entries.add(new ZipEntryData(name, null));
                    String noSlash = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    if (!noSlash.contains("/")) {
                        topDirs.add(noSlash);
                    }
                } else {
                    entries.add(new ZipEntryData(name, zis.readAllBytes()));
                    if (!name.contains("/")) {
                        topDirs.add(name);
                    }
                }
            }
        }

        // 判断是否需要跳过顶层目录
        String stripPrefix = null;
        if (topDirs.size() == 1) {
            String candidate = topDirs.iterator().next() + "/";
            boolean allUnder = entries.stream().allMatch(e ->
                    e.name.startsWith(candidate) || e.name.equals(candidate.substring(0, candidate.length() - 1)));
            if (allUnder) {
                stripPrefix = candidate;
                log.info("检测到 zip 单层根目录「{}」，自动跳过", topDirs.iterator().next());
            }
        }

        // 写入文件
        for (ZipEntryData zd : entries) {
            String name = zd.name;
            if (stripPrefix != null && name.startsWith(stripPrefix)) {
                name = name.substring(stripPrefix.length());
            }
            if (name.isEmpty()) continue;

            File newFile = new File(targetDir, name);
            if (zd.data == null) {
                newFile.mkdirs();
            } else {
                new File(newFile.getParent()).mkdirs();
                try (FileOutputStream fos = new FileOutputStream(newFile)) {
                    fos.write(zd.data);
                }
            }
        }
    }

    /**
     * 单文件产物（jar/binary）上传登记：文件已落盘后调用，写入产物注册表（version+1）
     */
    public void registerArtifact(Long serviceId, String type, File savedFile) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            return;
        }
        String regType = switch (type == null ? "" : type) {
            case "jar" -> "JAR";
            case "binary" -> "BINARY";
            default -> null; // 其他类型不纳入产物注册表
        };
        if (regType == null) {
            return;
        }
        try {
            long size = savedFile.length();
            String checksum;
            try (InputStream in = Files.newInputStream(savedFile.toPath())) {
                checksum = DigestUtil.sha256Hex(in);
            }
            artifactRegistry.register(service.getId(), service.getDeployName(), regType,
                    savedFile.getName(), savedFile.getAbsolutePath(), size, checksum);
            log.info("[{}] 产物已登记: type={}, size={}B, checksum={}", service.getName(), regType, size, checksum);
        } catch (Exception e) {
            log.warn("[{}] 产物登记失败: type={}, err={}", service.getName(), regType, e.getMessage());
        }
    }

    /**
     * dist 目录登记：解压完成后调用，按确定性打包流计算大小与校验和
     */
    public void registerDist(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            return;
        }
        File distDir = new File(service.getVolumeDir(), "dist");
        if (!distDir.exists() || !distDir.isDirectory()) {
            log.warn("[{}] dist 目录不存在，跳过登记: {}", service.getName(), distDir);
            return;
        }
        DeployArtifact tmp = new DeployArtifact();
        tmp.setStoragePath(distDir.getAbsolutePath());
        try (ArtifactStore.PreparedArtifact prepared = artifactStore.prepare(tmp)) {
            artifactRegistry.register(service.getId(), service.getDeployName(), "DIST",
                    "dist", distDir.getAbsolutePath(), prepared.size(), prepared.checksum());
            log.info("[{}] dist 产物已登记: size={}B(packed), checksum={}", service.getName(),
                    prepared.size(), prepared.checksum());
        } catch (Exception e) {
            log.warn("[{}] dist 产物登记失败: err={}", service.getName(), e.getMessage());
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
