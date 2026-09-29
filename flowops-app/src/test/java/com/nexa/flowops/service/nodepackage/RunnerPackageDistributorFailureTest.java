package com.nexa.flowops.service.nodepackage;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 传输阶段失败判定测试（阶段 2 P3）：用受控 SFTP 客户端确定性地覆盖
 * {@code UPLOAD_TIMEOUT}、{@code UPLOAD_INTERRUPTED}、{@code REMOTE_DIR_NOT_WRITABLE}、
 * {@code REMOTE_WRITE_FAILED} 四条分支（真实链路见 {@code RunnerPackageDistributionLinkTest}）。
 */
class RunnerPackageDistributorFailureTest {

    @TempDir
    Path tempDir;

    private RunnerPackageSettings settings;
    private RunnerPackageDistributor distributor;
    private Path localFile;

    @BeforeEach
    void setUp() throws Exception {
        settings = new RunnerPackageSettings();
        settings.setRemoteDir("/opt/flowops/runner/packages");
        distributor = new RunnerPackageDistributor(mock(RunnerPackageSshClient.class), settings);
        localFile = tempDir.resolve("local.tar.gz");
        Files.write(localFile, new byte[64 * 1024]);
    }

    @Test
    void reportsUploadTimeoutWhenTransferExceedsDeadline() throws Exception {
        settings.setUploadTimeout(Duration.ofMillis(100));
        SftpClient sftp = mock(SftpClient.class);
        when(sftp.write(any(String.class))).thenReturn(blockingStream());

        PackageDistributionFailure failure = assertThrows(PackageDistributionFailure.class,
                () -> distributor.upload(sftp, mock(ClientSession.class),
                        mock(RunnerPackageSshClient.SshConnection.class), localFile, "/tmp/x.part"));

        assertEquals(PackageDistributionErrorCode.UPLOAD_TIMEOUT.name(), failure.errorCode());
    }

    @Test
    void classifiesUploadFailuresByCauseAndSessionState() throws Exception {
        // 权限拒绝：目标机目录当前账号不可写
        SftpClient denied = mock(SftpClient.class);
        when(denied.write(any(String.class)))
                .thenThrow(new SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "denied"));
        ClientSession openSession = mock(ClientSession.class);
        when(openSession.isOpen()).thenReturn(true);
        assertFailure(PackageDistributionErrorCode.REMOTE_DIR_NOT_WRITABLE, denied, openSession);

        // 其它写失败：会话仍在，归为远端写入失败
        SftpClient failing = mock(SftpClient.class);
        when(failing.write(any(String.class))).thenThrow(new IOException("broken"));
        assertFailure(PackageDistributionErrorCode.REMOTE_WRITE_FAILED, failing, openSession);

        // 会话已断开：传输中断
        ClientSession closedSession = mock(ClientSession.class);
        when(closedSession.isOpen()).thenReturn(false);
        assertFailure(PackageDistributionErrorCode.UPLOAD_INTERRUPTED, failing, closedSession);
    }

    private void assertFailure(PackageDistributionErrorCode expected, SftpClient sftp, ClientSession session) {
        PackageDistributionFailure failure = assertThrows(PackageDistributionFailure.class,
                () -> distributor.upload(sftp, session,
                        mock(RunnerPackageSshClient.SshConnection.class), localFile, "/tmp/x.part"));
        assertEquals(expected.name(), failure.errorCode());
        assertEquals(expected.message(), failure.errorMessage());
    }

    /** 永远阻塞的远端输出流：模拟传输卡住。 */
    private static OutputStream blockingStream() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                sleepForever();
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                sleepForever();
            }

            private void sleepForever() throws IOException {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        };
    }
}
