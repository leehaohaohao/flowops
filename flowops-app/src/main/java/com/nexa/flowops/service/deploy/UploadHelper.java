package com.nexa.flowops.service.deploy;

import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

    public UploadHelper(DeployServiceMapper serviceMapper) {
        this.serviceMapper = serviceMapper;
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
