package com.nexa.flowops.service.artifact;

import com.nexa.flowops.common.util.DigestUtil;
import com.nexa.flowops.entity.DeployArtifact;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地产物存储：storage_path 指向主节点现 volumeDir。
 * 单文件（JAR/BINARY）直读，校验和直接用上传时登记值；DIST 目录按固定规则打包
 * （排序 + 固定 mtime）到临时 tar，保证打包字节稳定，传输侧可复用同一校验和。
 */
@Component
public class LocalArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(LocalArtifactStore.class);

    private static final String TMP_PREFIX = "flowops-dist-";
    private static final String TMP_SUFFIX = ".tar";

    @Override
    public boolean exists(DeployArtifact artifact) {
        return artifact != null && new File(artifact.getStoragePath()).exists();
    }

    @Override
    public PreparedArtifact prepare(DeployArtifact artifact) throws IOException {
        if (artifact == null || artifact.getStoragePath() == null) {
            throw new IOException("产物元数据缺失");
        }
        File target = new File(artifact.getStoragePath());
        if (!target.exists()) {
            throw new IOException("产物不存在: " + artifact.getStoragePath());
        }
        if (target.isFile()) {
            // 单文件内容自上传后不变，直接用注册表校验和，避免每次传输重算
            return new PreparedArtifact(Files.newInputStream(target.toPath()), target.length(),
                    artifact.getChecksum(), null);
        }
        return prepareDirectory(target);
    }

    private PreparedArtifact prepareDirectory(File dir) throws IOException {
        File pack = Files.createTempFile(TMP_PREFIX, TMP_SUFFIX).toFile();
        try {
            try (OutputStream out = Files.newOutputStream(pack.toPath());
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                packDirectory(tar, dir.toPath(), dir.toPath());
                tar.finish();
            }
            String checksum;
            try (InputStream in = Files.newInputStream(pack.toPath())) {
                checksum = DigestUtil.sha256Hex(in);
            }
            return new PreparedArtifact(Files.newInputStream(pack.toPath()), pack.length(), checksum, pack);
        } catch (IOException e) {
            pack.delete();
            throw e;
        }
    }

    /**
     * 确定性打包：条目按路径排序、mtime 固定为 0，保证多次打包字节一致
     */
    private void packDirectory(TarArchiveOutputStream tar, Path root, Path dir) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            List<Path> sorted = children.sorted().toList();
            for (Path path : sorted) {
                String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                File f = path.toFile();
                if (f.isDirectory()) {
                    TarArchiveEntry entry = new TarArchiveEntry(relative + "/");
                    entry.setModTime(0);
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                    packDirectory(tar, root, path);
                } else {
                    TarArchiveEntry entry = new TarArchiveEntry(relative);
                    entry.setSize(f.length());
                    entry.setModTime(0);
                    tar.putArchiveEntry(entry);
                    Files.copy(path, tar);
                    tar.closeArchiveEntry();
                }
            }
        }
    }
}
