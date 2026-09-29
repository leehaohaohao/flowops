package com.nexa.flowops.service.nodepackage;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 发布包不可变存储（阶段 2 P3）：默认目录 {@code /data/flowops/runner-packages}。
 *
 * <p>规则：
 * <ul>
 *   <li>正式文件名为 {@code <sha256>.tar.gz}，摘要即身份，**不覆盖**已存在的同名文件；</li>
 *   <li>上传先落到同目录的 {@code .upload-*.part} 临时文件，全部校验通过后原子改名；</li>
 *   <li>失败路径与进程异常退出的 {@code .upload-*.part} 都是垃圾，失败时立即删除、启动时统一清理；</li>
 *   <li>{@code sha256} 只允许 64 位小写十六进制，避免任何路径穿越。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LocalRunnerPackageStore {

    private static final Logger log = LoggerFactory.getLogger(LocalRunnerPackageStore.class);

    private static final String TEMP_PREFIX = ".upload-";
    private static final String TEMP_SUFFIX = ".part";
    private static final String PACKAGE_SUFFIX = ".tar.gz";

    private final RunnerPackageSettings settings;

    /** 存储目录（应用自行创建；与 deploy-prod.sh 挂载的 /data/flowops 一致）。 */
    public Path storeDir() {
        return Paths.get(settings.getStoreDir()).toAbsolutePath().normalize();
    }

    /** 创建一个上传临时文件（同目录，保证后续原子改名不跨文件系统）。 */
    public Path createTempFile() {
        Path dir = ensureStoreDir();
        try {
            return Files.createTempFile(dir, TEMP_PREFIX, TEMP_SUFFIX);
        } catch (IOException e) {
            log.warn("[RunnerPackage] 创建上传临时文件失败: {}", e.getClass().getSimpleName());
            throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
        }
    }

    /** 正式包路径；{@code sha256} 非法直接拒绝（不返回任何服务器绝对路径给调用方响应）。 */
    public Path packagePath(String sha256) {
        if (!RunnerPackageValidator.isSha256Hex(sha256)) {
            throw new RunnerPackageException(RunnerPackageFailure.SHA256_INVALID);
        }
        return storeDir().resolve(sha256 + PACKAGE_SUFFIX);
    }

    public boolean exists(String sha256) {
        return Files.isRegularFile(packagePath(sha256));
    }

    /** 已存储的正式包；不存在按 404 处理。 */
    public Path storedPackage(String sha256) {
        Path path = packagePath(sha256);
        if (!Files.isRegularFile(path)) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NOT_FOUND, sha256);
        }
        return path;
    }

    /**
     * 把校验通过的临时文件原子改名为正式包。
     *
     * <p>目标已存在（同摘要）时保留既有文件并删除临时文件——摘要即身份，内容必然相同。
     * 并发同摘要上传时，"检查存在"与"改名"之间对手可能已经落盘：此时 {@code ATOMIC_MOVE}
     * 会以"目标已存在"失败，这里按**幂等成功**处理（保留既有文件），不把并发变成存储故障。
     *
     * @return 正式包路径
     */
    public Path commit(Path tempFile, String sha256) {
        Path target = packagePath(sha256);
        if (Files.exists(target)) {
            deleteQuietly(tempFile);
            return target;
        }
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException concurrentWrite) {
            deleteQuietly(tempFile);
            return target;
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tempFile, target);
            } catch (FileAlreadyExistsException concurrentWrite) {
                deleteQuietly(tempFile);
                return target;
            } catch (IOException fallback) {
                log.warn("[RunnerPackage] 入库改名失败: {}", fallback.getClass().getSimpleName());
                deleteQuietly(tempFile);
                throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
            }
        } catch (IOException e) {
            if (Files.exists(target)) {
                // 竞态兜底：目标已经出现即视为已入库
                deleteQuietly(tempFile);
                return target;
            }
            log.warn("[RunnerPackage] 入库改名失败: {}", e.getClass().getSimpleName());
            deleteQuietly(tempFile);
            throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
        }
        return target;
    }

    /**
     * 正式包内容是否可用：文件存在、大小等于期望值、SHA-256 等于摘要（R2）。
     *
     * <p>数据库行与卷文件可能失同步（文件被删、被截断、内容被改），仅按文件名"存在"不足以证明内容等于摘要。
     *
     * @param expectedSize 期望字节数（取自索引行）；{@code null} 表示只核对摘要
     */
    public boolean isHealthy(String sha256, Long expectedSize) {
        Path path = packagePath(sha256);
        try {
            if (!Files.isRegularFile(path)) {
                return false;
            }
            if (expectedSize != null && Files.size(path) != expectedSize) {
                return false;
            }
            return sha256.equalsIgnoreCase(PackageDigests.sha256Hex(path));
        } catch (IOException e) {
            log.warn("[RunnerPackage] 复核存储文件失败: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 用已校验的临时文件**原子替换**正式包（仅用于修复缺失/损坏文件，R2）。
     *
     * <p>不允许原地截断：替换始终走"同目录临时文件 + 原子改名"，失败时删除临时文件。
     * 健康文件不走这里（由 {@link #commit} 的保留语义保护）。
     *
     * @return 正式包路径
     */
    public Path commitReplacing(Path tempFile, String sha256) {
        Path target = packagePath(sha256);
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallback) {
                log.warn("[RunnerPackage] 修复改名失败: {}", fallback.getClass().getSimpleName());
                deleteQuietly(tempFile);
                throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
            }
        } catch (IOException e) {
            log.warn("[RunnerPackage] 修复改名失败: {}", e.getClass().getSimpleName());
            deleteQuietly(tempFile);
            throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
        }
        return target;
    }

    /** 删除文件；失败只记日志（清理不影响主流程的失败原因）。 */
    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[RunnerPackage] 删除临时文件失败: {}", e.getClass().getSimpleName());
        }
    }

    /** 清理存储目录中所有 {@code .upload-*.part}（它们永远不是有效包）。 */
    public int cleanupTempFiles() {
        Path dir = storeDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)) {
                    deleteQuietly(path);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("[RunnerPackage] 清理上传临时文件失败: {}", e.getClass().getSimpleName());
        }
        return removed;
    }

    private Path ensureStoreDir() {
        Path dir = storeDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("[RunnerPackage] 创建存储目录失败: {}", e.getClass().getSimpleName());
            throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
        }
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) {
            throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
        }
        return dir;
    }
}
