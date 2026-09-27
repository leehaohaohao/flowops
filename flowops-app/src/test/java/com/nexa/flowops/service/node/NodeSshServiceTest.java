package com.nexa.flowops.service.node;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nexa.flowops.dto.SshTargetRequest;
import com.nexa.flowops.dto.SshTargetVO;
import com.nexa.flowops.dto.SshTestResultVO;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.entity.NexaNodeSshTarget;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.mapper.NexaNodeSshTargetMapper;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点 SSH 连接验证的行为测试：用 Apache MINA SSHD 起内嵌服务器，覆盖成功、主机密钥不符、
 * 认证失败、握手超时、私钥缺失/权限过宽/别名非法/不可读、只读命令失败、SFTP 失败等场景。
 *
 * <p>契约见 docs/2026-09-27-node-ssh-connection-plan.md（S1 定稿）。
 */
class NodeSshServiceTest {

    private static final String RUNNER_ID = "runner-1";
    private static final String KEY_ALIAS = "runner-1-key";
    /** 私钥解析失败时不会发起连接，端口取值无关紧要 */
    private static final int UNUSED_PORT = 22;

    @TempDir
    Path keyDir;

    private SshServer server;
    private KeyPair hostKey;
    private KeyPair clientKey;
    private NexaNodeMapper nodeMapper;
    private NexaNodeSshTargetMapper targetMapper;

    @BeforeEach
    void setUp() throws Exception {
        // MyBatis-Plus 的 LambdaUpdateWrapper 需要实体元信息缓存；纯单测（无 Spring 上下文）需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NexaNodeSshTarget.class);

        hostKey = generateKeyPair();
        clientKey = generateKeyPair();
        nodeMapper = mock(NexaNodeMapper.class);
        targetMapper = mock(NexaNodeSshTargetMapper.class);
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(new NexaNode());
        // 写回结果的条件更新默认成功；过期场景的用例单独覆盖返回 0
        when(targetMapper.update(any(), any())).thenReturn(1);
        writeClientKey(KEY_ALIAS, clientKey);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(true);
            server = null;
        }
    }

    // ==================== 正常与失败场景 ====================

    @Test
    void connectsWhenHostKeyAuthCommandAndSftpAreAllGood() throws Exception {
        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(hostKey, KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.CONNECTED.name(), result.getResultCode());
        assertEquals(SshTestResultCode.CONNECTED.message(), result.getMessage());
        assertNotNull(result.getTestedAt());
        assertTrue(result.getDurationMs() >= 0);
        // 结果落库：第 1 次 update 登记测试序号，第 2 次写回 last_*（刷新页面后仍可展示）
        verify(targetMapper, org.mockito.Mockito.times(2)).update(isNull(), any());
    }

    @Test
    void failsOnHostKeyMismatchAndStopsBeforeAuth() throws Exception {
        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        // 配置的指纹与服务器实际主机密钥不符
        targetOnServerPort(generateKeyPair(), KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.HOST_KEY_MISMATCH.name(), result.getResultCode());
    }

    @Test
    void failsWhenServerRejectsThePublicKey() throws Exception {
        startServer(acceptOnly(generateKeyPair()), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(hostKey, KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.AUTH_FAILED.name(), result.getResultCode());
    }

    @Test
    void timesOutWhenPeerNeverCompletesHandshake() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            // 只接受 TCP 连接、不回 SSH 握手，制造确定性的握手超时
            Thread acceptor = new Thread(() -> {
                try (Socket ignored = silent.accept()) {
                    Thread.sleep(5000);
                } catch (Exception ignored) {
                    // 用例结束即关闭
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            SshSettings settings = defaultSettings();
            settings.setConnectTimeout(Duration.ofMillis(400));
            targetOnPort(hostKey, silent.getLocalPort(), KEY_ALIAS);

            SshTestResultVO result = service(settings).test(RUNNER_ID);

            assertEquals(SshTestResultCode.CONNECT_TIMEOUT.name(), result.getResultCode());
        }
    }

    @Test
    void failsWhenReadOnlyCommandExitsNonZero() throws Exception {
        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), 1);
        targetOnServerPort(hostKey, KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.COMMAND_FAILED.name(), result.getResultCode());
    }

    @Test
    void failsWhenSftpSubsystemIsUnavailable() throws Exception {
        // 不注册任何子系统：SFTP 通道打开被拒绝
        startServer(acceptOnly(clientKey), List.of(), null);
        targetOnServerPort(hostKey, KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.SFTP_FAILED.name(), result.getResultCode());
    }

    // ==================== 私钥相关 ====================

    @Test
    void failsWhenPrivateKeyFileIsMissing() throws Exception {
        targetOnPort(hostKey, UNUSED_PORT, KEY_ALIAS);
        Files.delete(keyFile(KEY_ALIAS));

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.KEY_NOT_FOUND.name(), result.getResultCode());
    }

    @Test
    void failsWhenPrivateKeyIsNotReadable() throws Exception {
        targetOnPort(hostKey, UNUSED_PORT, KEY_ALIAS);
        Files.writeString(keyFile(KEY_ALIAS), "-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----\n");

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.KEY_UNREADABLE.name(), result.getResultCode());
    }

    @Test
    void rejectsAliasEscapingTheKeyDirectory() throws Exception {
        targetOnPort(hostKey, UNUSED_PORT, "../../etc/passwd");

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.KEY_ALIAS_INVALID.name(), result.getResultCode());
    }

    @Test
    void rejectsAliasPointingToDirectory() throws Exception {
        Files.createDirectory(keyDir.resolve("a-directory"));
        targetOnPort(hostKey, UNUSED_PORT, "a-directory");

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.KEY_NOT_FOUND.name(), result.getResultCode());
    }

    @Test
    void failsWhenKeyPermissionsAreTooOpen() throws Exception {
        targetOnPort(hostKey, UNUSED_PORT, KEY_ALIAS);

        // 非 POSIX 文件系统（本地 Windows）无法设置权限，用子类固定判定为"过宽"，
        // 验证该分支确实映射为 KEY_PERMISSION_TOO_OPEN；真实权限读取由 Linux/CI 覆盖
        SshTestResultVO result = new NodeSshService(nodeMapper, targetMapper,
                new NodeSshTestRegistry(targetMapper), defaultSettings()) {
            @Override
            boolean isPermissionTooOpen(Path keyFile) {
                return true;
            }
        }.test(RUNNER_ID);

        assertEquals(SshTestResultCode.KEY_PERMISSION_TOO_OPEN.name(), result.getResultCode());
    }

    // ==================== 前置条件与参数校验 ====================

    @Test
    void rejectsUnregisteredNode() {
        when(nodeMapper.selectById("ghost")).thenReturn(null);

        SshTargetException e = assertThrows(SshTargetException.class, () -> service(defaultSettings()).get("ghost"));

        assertEquals(SshTargetException.CODE_NOT_REGISTERED, e.getCode());
    }

    @Test
    void rejectsTestWithoutSavedTarget() {
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(null);

        SshTargetException e = assertThrows(SshTargetException.class, () -> service(defaultSettings()).test(RUNNER_ID));

        assertEquals(SshTargetException.CODE_INVALID, e.getCode());
        assertEquals("尚未配置 SSH 目标", e.getMessage());
    }

    @Test
    void validatesRequestFields() {
        NodeSshService service = service(defaultSettings());
        String validFingerprint = fingerprint(hostKey);

        assertInvalid(() -> service.save(RUNNER_ID, null));
        assertInvalid(() -> service.save(RUNNER_ID, request("ssh://host", 22, "root", KEY_ALIAS, validFingerprint)));
        assertInvalid(() -> service.save(RUNNER_ID, request("host", 70000, "root", KEY_ALIAS, validFingerprint)));
        assertInvalid(() -> service.save(RUNNER_ID, request("host", 22, "root; rm -rf /", KEY_ALIAS, validFingerprint)));
        assertInvalid(() -> service.save(RUNNER_ID, request("host", 22, "root", "../../etc/passwd", validFingerprint)));
        assertInvalid(() -> service.save(RUNNER_ID, request("host", 22, "root", KEY_ALIAS, "not-a-fingerprint")));
        // H1.2：算法必填且必须是受支持取值
        assertInvalid(() -> service.save(RUNNER_ID, request("host", 22, "root", KEY_ALIAS, validFingerprint, null)));
        assertInvalid(() -> service.save(RUNNER_ID, request("host", 22, "root", KEY_ALIAS, validFingerprint, "DSA")));
        assertInvalid(() -> service.save(RUNNER_ID, request("host", 22, "root", KEY_ALIAS, validFingerprint, "")));
    }

    @Test
    void normalisesHostKeyAlgorithmOnSave() {
        NexaNodeSshTarget saved = new NexaNodeSshTarget();
        saved.setRunnerId(RUNNER_ID);
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(null, saved);

        service(defaultSettings()).save(RUNNER_ID,
                request("10.0.0.5", 22, "root", KEY_ALIAS, fingerprint(hostKey), "ed25519"));

        var captor = org.mockito.ArgumentCaptor.forClass(NexaNodeSshTarget.class);
        verify(targetMapper).insert(captor.capture());
        assertEquals("ED25519", captor.getValue().getHostKeyAlgorithm());
    }

    @Test
    void keepsPortDefaultAndNormalisesFingerprintOnSave() throws Exception {
        NodeSshService service = service(defaultSettings());
        // 保存后服务会回读一次用于返回 VO；首次存在性判断需返回 null，故按调用顺序给出两个返回值
        NexaNodeSshTarget saved = new NexaNodeSshTarget();
        saved.setRunnerId(RUNNER_ID);
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(null, saved);

        service.save(RUNNER_ID, request("10.0.0.5", null, "root", KEY_ALIAS,
                fingerprint(hostKey).substring("SHA256:".length()) + "=="));

        var captor = org.mockito.ArgumentCaptor.forClass(NexaNodeSshTarget.class);
        verify(targetMapper).insert(captor.capture());
        assertEquals(22, captor.getValue().getPort());
        assertEquals(fingerprint(hostKey), captor.getValue().getHostKeySha256());
        assertEquals("10.0.0.5", captor.getValue().getHost());
        assertEquals(RUNNER_ID, captor.getValue().getRunnerId());
    }

    @Test
    void normalisesFingerprintFormat() throws Exception {
        String raw = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.getPublic());
        String body = raw.substring("SHA256:".length());

        assertEquals(raw, NodeSshService.normalizeFingerprint(body));
        assertEquals(raw, NodeSshService.normalizeFingerprint(raw));
        assertEquals(raw, NodeSshService.normalizeFingerprint("sha256:" + body));
        assertEquals(raw, NodeSshService.normalizeFingerprint(raw + "=="));
        // 与 ssh-keyscan / ssh -o FingerprintHash=sha256 输出一致：SHA256: + 无填充 base64（32 字节 → 43 字符）
        assertEquals(43, body.length());
    }

    // ==================== 敏感信息不入日志 ====================

    @Test
    void neverLogsPrivateKeyMaterialOrPaths() throws Exception {
        startServer(acceptOnly(generateKeyPair()), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(hostKey, KEY_ALIAS);

        String keyBody = Files.readString(keyFile(KEY_ALIAS));
        String keySample = keyBody.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        keySample = keySample.substring(0, Math.min(40, keySample.length()));

        ListAppender<ILoggingEvent> appender = attachAppender(NodeSshService.class);
        String responseMessage;
        try {
            responseMessage = service(defaultSettings()).test(RUNNER_ID).getMessage();
        } finally {
            detachAppender(NodeSshService.class, appender);
        }

        String logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);

        assertFalse(logged.contains(keySample), "私钥内容不得进入日志");
        assertFalse(logged.contains("PRIVATE KEY"), "私钥块标记不得进入日志");
        assertFalse(responseMessage.contains(keyDir.toString()), "响应不得包含服务器绝对路径");
        assertFalse(responseMessage.contains("PRIVATE KEY"), "响应不得包含私钥块");
    }

    // ==================== R1：测试结果与配置版本的并发保护 ====================

    @Test
    void keepsOnlyLatestTestResultWhenEarlierTestFinishesLast() throws Exception {
        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(hostKey, KEY_ALIAS);
        // 调用顺序：A 登记(1) → A 写回被拒(0，期间 B 已登记) → B 登记(1) → B 写回成功(1)
        when(targetMapper.update(any(), any())).thenReturn(1, 0, 1, 1);
        NodeSshService service = service(defaultSettings());

        SshTestResultVO older = service.test(RUNNER_ID);
        SshTestResultVO newer = service.test(RUNNER_ID);

        assertEquals(SshTestResultCode.TEST_OBSOLETE.name(), older.getResultCode(), "较早的测试不得写回结果");
        assertEquals(SshTestResultCode.CONNECTED.name(), newer.getResultCode());
    }

    @Test
    void rejectsResultWhenConfigChangedDuringTest() throws Exception {
        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(hostKey, KEY_ALIAS);
        // 测试期间执行了 PUT /ssh：版本已变，写回条件不匹配
        when(targetMapper.update(any(), any())).thenReturn(1, 0);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.TEST_OBSOLETE.name(), result.getResultCode());
        assertEquals(SshTestResultCode.TEST_OBSOLETE.message(), result.getMessage());
    }

    @Test
    void writeBackGuardChecksConfigVersionAndTestSequence() throws Exception {
        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(hostKey, KEY_ALIAS);

        service(defaultSettings()).test(RUNNER_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(targetMapper, org.mockito.Mockito.times(2)).update(isNull(), captor.capture());
        String where = String.valueOf(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) captor.getAllValues().get(1)).getSqlSegment());

        assertTrue(where.contains("config_version"), "写回必须校验配置版本，实际: " + where);
        assertTrue(where.contains("latest_test_seq"), "写回必须校验最新测试序号，实际: " + where);
    }

    @Test
    void savingSettingsBumpsVersionAndClearsTestState() {
        NexaNodeSshTarget existing = new NexaNodeSshTarget();
        existing.setRunnerId(RUNNER_ID);
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(existing);

        service(defaultSettings()).save(RUNNER_ID, request("10.0.0.5", 22, "root", KEY_ALIAS, fingerprint(hostKey)));

        var captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(targetMapper).update(isNull(), captor.capture());
        var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) captor.getValue();
        String sqlSet = String.valueOf(wrapper.getSqlSet());

        assertTrue(sqlSet.contains("config_version = config_version + 1"), "覆盖设置必须递增版本，实际: " + sqlSet);
        assertTrue(sqlSet.contains("latest_test_seq"), "覆盖设置必须清空测试序号，实际: " + sqlSet);
        assertTrue(sqlSet.contains("last_result_code"), "覆盖设置必须清空旧结果，实际: " + sqlSet);
    }

    // ==================== R3：符号链接与真实路径边界 ====================

    @Test
    void rejectsSymlinkAliasBeforeReading() throws Exception {
        targetOnPort(hostKey, UNUSED_PORT, KEY_ALIAS);

        // 无法创建符号链接的平台（如未开启开发者模式的 Windows）用可覆盖判定覆盖该分支
        SshTestResultVO result = new NodeSshService(nodeMapper, targetMapper,
                new NodeSshTestRegistry(targetMapper), defaultSettings()) {
            @Override
            boolean isSymlink(Path path) {
                return true;
            }
        }.test(RUNNER_ID);

        assertEquals(SshTestResultCode.KEY_ALIAS_INVALID.name(), result.getResultCode());
    }

    @Test
    void keyFileExistsAppliesTheSameSymlinkRuleAsReading() {
        targetOnPort(hostKey, UNUSED_PORT, KEY_ALIAS);

        SshTargetVO vo = new NodeSshService(nodeMapper, targetMapper,
                new NodeSshTestRegistry(targetMapper), defaultSettings()) {
            @Override
            boolean isSymlink(Path path) {
                return true;
            }
        }.get(RUNNER_ID);

        assertNotNull(vo);
        assertFalse(vo.isKeyFileExists(), "符号链接别名不得被当作可用私钥");
    }

    @Test
    void rejectsSymlinkPointingOutsideKeyDir() throws Exception {
        assumeSymlinksSupported();
        Path outsideDir = Files.createTempDirectory("flowops-outside-keys");
        Path outsideKey = outsideDir.resolve("id_rsa");
        try (OutputStream out = Files.newOutputStream(outsideKey)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKey, "outside", null, out);
        }
        Files.createSymbolicLink(keyFile("linked-outside"), outsideKey);
        targetOnPort(hostKey, UNUSED_PORT, "linked-outside");

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.KEY_ALIAS_INVALID.name(), result.getResultCode());
    }

    @Test
    void rejectsSymlinkPointingInsideKeyDir() throws Exception {
        assumeSymlinksSupported();
        Files.createSymbolicLink(keyFile("linked-inside"), keyFile(KEY_ALIAS));
        targetOnPort(hostKey, UNUSED_PORT, "linked-inside");

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.KEY_ALIAS_INVALID.name(), result.getResultCode());
    }

    /** 符号链接在部分平台需要额外权限；不支持时跳过（真实链接用例在 Linux/POSIX 执行）。 */
    private void assumeSymlinksSupported() {
        Path probe = keyDir.resolve(".symlink-probe");
        try {
            Files.createSymbolicLink(probe, keyFile(KEY_ALIAS));
            Files.deleteIfExists(probe);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "当前平台无法创建符号链接，改用强制判定用例覆盖: " + e.getMessage());
        }
    }

    // ==================== H1：多主机密钥与诊断信息 ====================

    @Test
    void verifiesEd25519HostKeyWhenServerAlsoOffersRsa() throws Exception {
        // H1 场景：目标机同时启用多把主机密钥，管理员填的是 Ed25519 指纹。
        // 实测客户端在两种顺序下都优先协商 EdDSA，因此这种情况必须连接成功。
        KeyPair ed25519 = generateEd25519KeyPair();
        startServer(new KeyPair[]{hostKey, ed25519}, acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(ed25519, KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.CONNECTED.name(), result.getResultCode());
    }

    @Test
    void logsNegotiatedAlgorithmAndObservedFingerprintOnMismatch() throws Exception {        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(generateKeyPair(), KEY_ALIAS); // 配置的指纹与服务器主机密钥不符

        ListAppender<ILoggingEvent> appender = attachAppender(NodeSshService.class);
        SshTestResultVO result;
        try {
            result = service(defaultSettings()).test(RUNNER_ID);
        } finally {
            detachAppender(NodeSshService.class, appender);
        }

        String logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);

        assertEquals(SshTestResultCode.HOST_KEY_MISMATCH.name(), result.getResultCode());
        assertTrue(logged.contains("协商算法="), "诊断日志必须记录协商到的算法，实际: " + logged);
        assertTrue(logged.contains("RSA/"), "应记录协商到的密钥类型，实际: " + logged);
        assertTrue(logged.contains(fingerprint(hostKey)), "应记录观察到的指纹，实际: " + logged);
        assertTrue(logged.contains("testId="), "诊断日志应与同一次测试关联，实际: " + logged);
        assertFalse(logged.contains("PRIVATE KEY"), "诊断日志不得包含密钥内容");
    }

    // ==================== H1.2/H1.3：显式主机密钥算法 ====================

    @Test
    void verifiesRsaHostKeyWhenServerAlsoOffersEd25519() throws Exception {
        KeyPair ed25519 = generateEd25519KeyPair();
        startServer(new KeyPair[]{ed25519, hostKey}, acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(hostKey, KEY_ALIAS); // hostKey 为 RSA，算法自动取 RSA

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.CONNECTED.name(), result.getResultCode());
    }

    @Test
    void reportsAlgorithmUnavailableWhenServerDoesNotOfferChosenAlgorithm() throws Exception {
        // 服务端只提供 RSA，配置为 ED25519 → 应报"算法不可用"，而不是"指纹不匹配"
        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetOnServerPort(generateEd25519KeyPair(), KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.HOST_KEY_ALGORITHM_UNAVAILABLE.name(), result.getResultCode());
    }

    @Test
    void reportsMismatchWhenFingerprintBelongsToAnotherAlgorithm() throws Exception {
        // 服务端同时提供 Ed25519 与 RSA；算法选 ED25519 却填了 RSA 的指纹 → 算法对、指纹错
        KeyPair ed25519 = generateEd25519KeyPair();
        startServer(new KeyPair[]{ed25519, hostKey}, acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetWith("ED25519", fingerprint(hostKey), server.getPort(), KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.HOST_KEY_MISMATCH.name(), result.getResultCode());
    }

    @Test
    void refusesLegacyRecordWithoutAlgorithm() throws Exception {
        startServer(acceptOnly(clientKey), List.of(new SftpSubsystemFactory()), null);
        targetWith(null, fingerprint(hostKey), server.getPort(), KEY_ALIAS);

        SshTestResultVO result = service(defaultSettings()).test(RUNNER_ID);

        assertEquals(SshTestResultCode.HOST_KEY_ALGORITHM_REQUIRED.name(), result.getResultCode());
    }

    @Test
    void matchesKeyTypeFamilyForAllAlgorithms() throws Exception {
        assertTrue(SshHostKeyAlgorithm.ED25519.matchesKeyType(KeyUtils.getKeyType(generateEd25519KeyPair().getPublic())));
        assertTrue(SshHostKeyAlgorithm.RSA.matchesKeyType(KeyUtils.getKeyType(hostKey.getPublic())));
        assertTrue(SshHostKeyAlgorithm.ECDSA.matchesKeyType(KeyUtils.getKeyType(generateEcKeyPair().getPublic())));

        assertFalse(SshHostKeyAlgorithm.ED25519.matchesKeyType(KeyUtils.getKeyType(hostKey.getPublic())));
        assertFalse(SshHostKeyAlgorithm.RSA.matchesKeyType(KeyUtils.getKeyType(generateEd25519KeyPair().getPublic())));
        assertFalse(SshHostKeyAlgorithm.ECDSA.matchesKeyType("not-a-key-type"));
        assertFalse(SshHostKeyAlgorithm.RSA.matchesKeyType(null));
    }

    // ==================== 工具 ====================

    private NodeSshService service(SshSettings settings) {
        return new NodeSshService(nodeMapper, targetMapper, new NodeSshTestRegistry(targetMapper), settings);
    }

    private SshSettings defaultSettings() {
        SshSettings settings = new SshSettings();
        settings.setKeyDir(keyDir.toString());
        settings.setConnectTimeout(Duration.ofSeconds(3));
        settings.setAuthTimeout(Duration.ofSeconds(3));
        settings.setCommandTimeout(Duration.ofSeconds(3));
        settings.setSftpTimeout(Duration.ofSeconds(3));
        return settings;
    }

    private void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        SshTargetException e = assertThrows(SshTargetException.class, executable);
        assertEquals(SshTargetException.CODE_INVALID, e.getCode());
    }

    private void targetOnServerPort(KeyPair configuredHostKey, String keyAlias) {
        assertNotNull(server, "用例需先启动内嵌 SSH 服务器");
        targetOnPort(configuredHostKey, server.getPort(), keyAlias);
    }

    private void targetOnPort(KeyPair configuredHostKey, int port, String keyAlias) {
        NexaNodeSshTarget target = new NexaNodeSshTarget();
        target.setRunnerId(RUNNER_ID);
        target.setHost("127.0.0.1");
        target.setPort(port);
        target.setUsername("root");
        target.setKeyAlias(keyAlias);
        target.setHostKeySha256(fingerprint(configuredHostKey));
        target.setHostKeyAlgorithm(algorithmOf(configuredHostKey));
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(target);
    }

    /** 按主机密钥类型给出对应的算法（H1.2 要求算法与指纹成对）。 */
    private String algorithmOf(KeyPair hostKeyPair) {
        String keyType = KeyUtils.getKeyType(hostKeyPair.getPublic());
        return SshHostKeyAlgorithm.ED25519.matchesKeyType(keyType) ? "ED25519"
                : SshHostKeyAlgorithm.RSA.matchesKeyType(keyType) ? "RSA"
                : "ECDSA";
    }

    private String fingerprint(KeyPair keyPair) {
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, keyPair.getPublic());
    }

    private SshTargetRequest request(String host, Integer port, String username, String keyAlias, String hostKeySha256) {
        return request(host, port, username, keyAlias, hostKeySha256, "ED25519");
    }

    private SshTargetRequest request(String host, Integer port, String username, String keyAlias,
                                     String hostKeySha256, String hostKeyAlgorithm) {
        SshTargetRequest request = new SshTargetRequest();
        request.setHost(host);
        request.setPort(port);
        request.setUsername(username);
        request.setKeyAlias(keyAlias);
        request.setHostKeySha256(hostKeySha256);
        request.setHostKeyAlgorithm(hostKeyAlgorithm);
        return request;
    }

    private void startServer(PublickeyAuthenticator authenticator,
                             List<SubsystemFactory> subsystems,
                             Integer commandExitStatus) throws IOException {
        startServer(new KeyPair[]{hostKey}, authenticator, subsystems, commandExitStatus);
    }

    private void startServer(KeyPair[] hostKeys,
                             PublickeyAuthenticator authenticator,
                             List<SubsystemFactory> subsystems,
                             Integer commandExitStatus) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKeys));
        server.setPublickeyAuthenticator(authenticator);
        server.setSubsystemFactories(subsystems);
        // 命令工厂必须配置：否则 exec 请求失败，通道不关闭
        server.setCommandFactory((channel, command) -> fixedExitCommand(commandExitStatus == null ? 0 : commandExitStatus));
        server.start();
    }

    private PublickeyAuthenticator acceptOnly(KeyPair allowed) {
        byte[] allowedEncoded = allowed.getPublic().getEncoded();
        return (username, key, session) -> Arrays.equals(allowedEncoded, key.getEncoded());
    }

    /** 只读命令固定返回给定退出码，用于验证 COMMAND_FAILED。 */
    private Command fixedExitCommand(int exitStatus) {
        return new Command() {
            private ExitCallback callback;

            @Override
            public void setInputStream(InputStream in) {
            }

            @Override
            public void setOutputStream(OutputStream out) {
            }

            @Override
            public void setErrorStream(OutputStream err) {
            }

            @Override
            public void setExitCallback(ExitCallback callback) {
                this.callback = callback;
            }

            @Override
            public void start(ChannelSession channel, Environment env) {
                callback.onExit(exitStatus);
            }

            @Override
            public void destroy(ChannelSession channel) {
            }
        };
    }

    private Path keyFile(String alias) {
        return keyDir.resolve(alias);
    }

    /** 以 ssh-keygen 默认的 OpenSSH 私钥格式写出，覆盖管理员最常见的密钥来源。 */
    private void writeClientKey(String alias, KeyPair keyPair) throws Exception {
        Path file = keyFile(alias);
        try (OutputStream out = Files.newOutputStream(file)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, alias, null, out);
        }
        if (Files.getFileStore(keyDir).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        }
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** SSHD 只认 EdDSA 提供者产生的 Ed25519 密钥类型（JCA 的 EdECPublicKey 无法被 SSHD 编码）。 */
    private KeyPair generateEd25519KeyPair() {
        return new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair();
    }

    private KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        return generator.generateKeyPair();
    }

    /** 构造并登记一个指定算法与指纹的目标，用于覆盖算法缺失/指纹错配等场景。 */
    private NexaNodeSshTarget targetWith(String hostKeyAlgorithm, String hostKeySha256, int port, String keyAlias) {
        NexaNodeSshTarget target = new NexaNodeSshTarget();
        target.setRunnerId(RUNNER_ID);
        target.setHost("127.0.0.1");
        target.setPort(port);
        target.setUsername("root");
        target.setKeyAlias(keyAlias);
        target.setHostKeySha256(hostKeySha256);
        target.setHostKeyAlgorithm(hostKeyAlgorithm);
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(target);
        return target;
    }

    private ListAppender<ILoggingEvent> attachAppender(Class<?> loggerClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        logger.detachAppender(appender);
    }
}
