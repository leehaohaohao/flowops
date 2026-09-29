package com.nexa.flowops.service.nodepackage;

import com.nexa.flowops.entity.NexaNodeSshTarget;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 发布包 SFTP 分发（阶段 2 P3，契约 §4 的"分发执行顺序"）。
 *
 * <p>只做一件事：把一个已校验的六成员通用包可靠送到目标机。边界：
 * <ul>
 *   <li>会话复用阶段 1 的严格主机密钥校验（见 {@link RunnerPackageSshClient}）；</li>
 *   <li>只写 {@code <sha256>.part} 与 {@code <sha256>.tar.gz} 两个固定名，只跑
 *       {@code sha256sum}/{@code df}/{@code mv}/{@code rm} 这几条固定命令，命令文本由固定目录 +
 *       64 位十六进制摘要拼成，**不含任何用户输入**；</li>
 *   <li><b>不解压</b>、不执行包内脚本、不写节点凭据、不启动子节点；</li>
 *   <li>失败保留既有正式包并清理本次临时文件；远端校验不符绝不改名。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RunnerPackageDistributor {

    private static final Logger log = LoggerFactory.getLogger(RunnerPackageDistributor.class);

    private final RunnerPackageSshClient sshClient;
    private final RunnerPackageSettings settings;

    /** 分发结果：{@code alreadyPresent=true} 表示目标机已有同摘要包，本次未传输。 */
    public record Outcome(boolean alreadyPresent, String remotePath) {
    }

    /** 目标机正式包路径（固定规则，也用于分发记录展示）。 */
    public String finalRemotePath(String sha256) {
        return normalizeDir(settings.getRemoteDir()) + "/" + sha256 + ".tar.gz";
    }

    /** 目标机临时文件路径（上传中，校验通过后原子改名为正式包）。 */
    public String partRemotePath(String sha256) {
        return normalizeDir(settings.getRemoteDir()) + "/" + sha256 + ".part";
    }

    /**
     * 执行一次分发。
     *
     * @param runnerId    目标节点（仅用于日志）
     * @param sha256      包摘要（同时是远端文件名）
     * @param packageFile 主节点已存储的包文件
     * @param sizeBytes   包大小（用于"已存在"的快速判断与空间预检）
     * @param target      已保存并已通过连接测试的 SSH 目标
     * @param stage       阶段回调：只在单调前进时触发（UPLOADING → VERIFYING）
     */
    public Outcome distribute(String runnerId, String sha256, Path packageFile, long sizeBytes,
                             NexaNodeSshTarget target, Consumer<PackageDistributionStatus> stage) {
        if (!Files.isRegularFile(packageFile)) {
            throw new PackageDistributionFailure(PackageDistributionErrorCode.STORE_READ_FAILED);
        }
        // R2：建立远端会话之前先证明"主节点存储的源文件仍等于摘要"。
        // 否则会把损坏的源文件当成传输失败（REMOTE_CHECKSUM_MISMATCH）并白传整个包。
        // 这一步在 connect() 之前，因此失败时目标机不会出现任何会话或文件。
        if (!isSourceHealthy(packageFile, sizeBytes, sha256)) {
            throw new PackageDistributionFailure(PackageDistributionErrorCode.STORE_CHECKSUM_MISMATCH);
        }
        String remoteDir = normalizeDir(settings.getRemoteDir());
        String partPath = partRemotePath(sha256);
        String finalPath = finalRemotePath(sha256);
        long commandTimeout = settings.getRemoteCommandTimeout().toMillis();

        try (RunnerPackageSshClient.SshConnection connection = sshClient.connect(target)) {
            ClientSession session = connection.session();
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                try {
                    requireRemoteDir(sftp, session, remoteDir);
                    if (isAlreadyPresent(sftp, session, finalPath, sizeBytes, sha256, commandTimeout)) {
                        stage.accept(PackageDistributionStatus.VERIFYING);
                        log.info("[RunnerPackage] 目标机已有同摘要包，跳过传输 runnerId={} sha256={}", runnerId, sha256);
                        return new Outcome(true, finalPath);
                    }
                    checkFreeSpace(session, remoteDir, sizeBytes, commandTimeout);
                    removeStalePart(sftp, partPath);

                    stage.accept(PackageDistributionStatus.UPLOADING);
                    upload(sftp, session, connection, packageFile, partPath);

                    stage.accept(PackageDistributionStatus.VERIFYING);
                    String remoteDigest = remoteSha256(session, partPath, commandTimeout);
                    if (!remoteDigest.equalsIgnoreCase(sha256)) {
                        throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_CHECKSUM_MISMATCH);
                    }
                    rename(session, partPath, finalPath, commandTimeout);
                    log.info("[RunnerPackage] 分发完成 runnerId={} sha256={} path={} size={}B",
                            runnerId, sha256, finalPath, sizeBytes);
                    return new Outcome(false, finalPath);
                } catch (RuntimeException | IOException e) {
                    cleanupPart(sftp, session, partPath);
                    throw e;
                }
            }
        } catch (PackageDistributionFailure e) {
            throw e;
        } catch (RunnerPackageSshClient.SshConnectionException e) {
            throw new PackageDistributionFailure(e.code());
        } catch (IOException e) {
            log.warn("[RunnerPackage] 分发 IO 失败 runnerId={} 原因={}", runnerId, e.getClass().getSimpleName());
            throw new PackageDistributionFailure(PackageDistributionErrorCode.UPLOAD_INTERRUPTED);
        }
    }

    /** 源文件是否等于摘要：大小一致且 SHA-256 相同（R2 的分发前本地校验）。 */
    private static boolean isSourceHealthy(Path packageFile, long expectedSize, String sha256) {
        try {
            return Files.size(packageFile) == expectedSize && sha256.equalsIgnoreCase(PackageDigests.sha256Hex(packageFile));
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 远端预检 ====================

    private void requireRemoteDir(SftpClient sftp, ClientSession session, String remoteDir) throws IOException {
        try {
            SftpClient.Attributes attributes = sftp.stat(remoteDir);
            if (!attributes.isDirectory()) {
                throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_DIR_MISSING);
            }
        } catch (PackageDistributionFailure e) {
            throw e;
        } catch (IOException e) {
            // 目录缺失由运维预置，主节点不创建、不改权限
            throw new PackageDistributionFailure(session.isOpen()
                    ? PackageDistributionErrorCode.REMOTE_DIR_MISSING
                    : PackageDistributionErrorCode.UPLOAD_INTERRUPTED);
        }
    }

    /** 目标机已有同摘要正式包且远端摘要一致 → 视为成功；无法校验则按需要重新传输处理。 */
    private boolean isAlreadyPresent(SftpClient sftp, ClientSession session, String finalPath,
                                     long sizeBytes, String sha256, long commandTimeout) {
        SftpClient.Attributes attributes;
        try {
            attributes = sftp.stat(finalPath);
        } catch (IOException e) {
            return false;
        }
        if (!attributes.isRegularFile() || attributes.getSize() != sizeBytes) {
            return false;
        }
        try {
            CommandResult result = exec(session, "sha256sum -- " + quote(finalPath), commandTimeout);
            if (result.exitStatus() != 0) {
                return false;
            }
            return sha256.equalsIgnoreCase(firstToken(result.stdout()));
        } catch (PackageDistributionFailure | IOException e) {
            log.info("[RunnerPackage] 远端既有包无法校验，将重新传输: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 空间预检：{@code df} 不可用或输出无法解析时跳过，最终由写入失败兜底。 */
    private void checkFreeSpace(ClientSession session, String remoteDir, long sizeBytes, long commandTimeout) {
        long required = sizeBytes + settings.getRemoteFreeSpaceMargin().toBytes();
        try {
            CommandResult result = exec(session, "df -Pk -- " + quote(remoteDir), commandTimeout);
            if (result.exitStatus() != 0) {
                log.info("[RunnerPackage] 远端空间预检不可用（df 退出码 {}），跳过", result.exitStatus());
                return;
            }
            Long availableKb = parseDfAvailableKb(result.stdout());
            if (availableKb == null) {
                log.info("[RunnerPackage] 远端空间预检输出无法解析，跳过");
                return;
            }
            if (availableKb * 1024L < required) {
                throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_DISK_INSUFFICIENT);
            }
        } catch (PackageDistributionFailure e) {
            if (PackageDistributionErrorCode.REMOTE_COMMAND_TIMEOUT.name().equals(e.errorCode())) {
                log.info("[RunnerPackage] 远端空间预检超时，跳过");
                return;
            }
            throw e;
        } catch (IOException e) {
            log.info("[RunnerPackage] 远端空间预检失败（{}），跳过", e.getClass().getSimpleName());
        }
    }

    private void removeStalePart(SftpClient sftp, String partPath) throws IOException {
        try {
            sftp.remove(partPath);
        } catch (SftpException e) {
            if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }
    }

    // ==================== 传输与校验 ====================

    /** SFTP 流式上传，受 {@code flowops.runner-package.upload-timeout} 限制；超时中断会话。 */
    void upload(SftpClient sftp, ClientSession session,
                RunnerPackageSshClient.SshConnection connection, Path local, String remote) {
        Duration timeout = settings.getUploadTimeout();
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runner-package-upload");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> future = worker.submit(() -> {
            try (OutputStream out = sftp.write(remote); InputStream in = Files.newInputStream(local)) {
                in.transferTo(out);
            }
            return null;
        });
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            connection.close();
            throw new PackageDistributionFailure(PackageDistributionErrorCode.UPLOAD_TIMEOUT);
        } catch (ExecutionException e) {
            throw mapUploadFailure(e.getCause(), session);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PackageDistributionFailure(PackageDistributionErrorCode.UPLOAD_INTERRUPTED);
        } finally {
            worker.shutdownNow();
        }
    }

    private PackageDistributionFailure mapUploadFailure(Throwable cause, ClientSession session) {
        if (cause instanceof SftpException sftpException
                && sftpException.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED) {
            return new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_DIR_NOT_WRITABLE);
        }
        if (!session.isOpen()) {
            return new PackageDistributionFailure(PackageDistributionErrorCode.UPLOAD_INTERRUPTED);
        }
        log.warn("[RunnerPackage] 远端写入失败: {}",
                cause == null ? "unknown" : cause.getClass().getSimpleName());
        return new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_WRITE_FAILED);
    }

    private String remoteSha256(ClientSession session, String path, long commandTimeout) throws IOException {
        CommandResult result = exec(session, "sha256sum -- " + quote(path), commandTimeout);
        if (result.exitStatus() == 127) {
            throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_TOOL_MISSING);
        }
        if (result.exitStatus() != 0) {
            throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_COMMAND_FAILED);
        }
        String token = firstToken(result.stdout());
        if (token == null || !RunnerPackageValidator.isSha256Hex(token.toLowerCase())) {
            throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_COMMAND_FAILED);
        }
        return token;
    }

    private void rename(ClientSession session, String from, String to, long commandTimeout) throws IOException {
        CommandResult result = exec(session, "mv -f -- " + quote(from) + " " + quote(to), commandTimeout);
        if (result.exitStatus() == 127) {
            throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_TOOL_MISSING);
        }
        if (result.exitStatus() != 0) {
            throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_COMMAND_FAILED);
        }
    }

    /** 失败路径清理本次临时文件：尽力而为，失败只记服务端日志，不覆盖原始失败原因。 */
    private void cleanupPart(SftpClient sftp, ClientSession session, String partPath) {
        if (!session.isOpen()) {
            log.warn("[RunnerPackage] 会话已断开，跳过远端临时文件清理");
            return;
        }
        try {
            sftp.remove(partPath);
        } catch (IOException e) {
            log.warn("[RunnerPackage] 清理远端临时文件失败: {}", e.getClass().getSimpleName());
        }
    }

    // ==================== 远端命令 ====================

    /**
     * 执行一条固定模板命令，捕获退出码与 stdout。
     *
     * <p>只有在超时（{@code REMOTE_COMMAND_TIMEOUT}）时才抛业务失败；非 0 退出码交给调用方判定
     * （127 视为缺少基础命令）。远端输出只用于判断，不进入 API 响应。
     */
    private CommandResult exec(ClientSession session, String command, long timeoutMs) throws IOException {
        try (ChannelExec channel = session.createExecChannel(command)) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.setErr(stderr);
            try {
                channel.open().verify(timeoutMs);
            } catch (Exception e) {
                throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_COMMAND_TIMEOUT);
            }
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeoutMs);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_COMMAND_TIMEOUT);
            }
            Integer exitStatus = channel.getExitStatus();
            return new CommandResult(exitStatus == null ? -1 : exitStatus,
                    stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private static Long parseDfAvailableKb(String stdout) {
        if (stdout == null) {
            return null;
        }
        String[] lines = stdout.strip().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] fields = lines[i].strip().split("\\s+");
            if (fields.length >= 4) {
                try {
                    return Long.parseLong(fields[3]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String firstToken(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /** 路径只来自固定配置目录与 64 位十六进制摘要，仍按 shell 习惯加单引号以防意外。 */
    private static String quote(String path) {
        return "'" + path + "'";
    }

    private static String normalizeDir(String dir) {
        String value = dir == null ? "" : dir.strip();
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record CommandResult(int exitStatus, String stdout, String stderr) {
    }
}
