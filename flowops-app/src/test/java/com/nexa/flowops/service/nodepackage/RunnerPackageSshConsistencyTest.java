package com.nexa.flowops.service.nodepackage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nexa.flowops.dto.SshTestResultVO;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.entity.NexaNodeSshTarget;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.mapper.NexaNodeSshTargetMapper;
import com.nexa.flowops.service.node.NodeSshService;
import com.nexa.flowops.service.node.NodeSshTestRegistry;
import com.nexa.flowops.service.node.SshHostKeyAlgorithm;
import com.nexa.flowops.service.node.SshSettings;
import com.nexa.flowops.service.node.SshTestResultCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSH 信任边界一致性测试（阶段 2 P3-C）：同一台目标机、同一份 SSH 设置、同一把私钥下，
 * 阶段 1 的连接测试（{@link NodeSshService}）与阶段 2 的分发会话
 * （{@link RunnerPackageSshClient}）必须给出**同一判定**。
 *
 * <p>两处各有一份实现（阶段 1 的行有独立允许范围，本行不改动它），本测试是防止二者漂移的回归护栏：
 * 一旦某处放宽或收紧校验（算法、指纹、私钥别名、权限），这里会失败。
 */
class RunnerPackageSshConsistencyTest {

    private static final String RUNNER_ID = "runner-1";
    private static final String KEY_ALIAS = "runner-1-key";

    @TempDir
    Path keyDir;

    private SshServer server;
    private KeyPair hostKey;
    private KeyPair clientKey;
    private NexaNodeMapper nodeMapper;
    private NexaNodeSshTargetMapper targetMapper;
    private SshSettings sshSettings;

    @BeforeEach
    void setUp() throws Exception {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NexaNodeSshTarget.class);

        hostKey = rsaKeyPair();
        clientKey = rsaKeyPair();
        nodeMapper = mock(NexaNodeMapper.class);
        targetMapper = mock(NexaNodeSshTargetMapper.class);
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(new NexaNode());
        when(targetMapper.update(isNull(), any())).thenReturn(1);

        sshSettings = new SshSettings();
        sshSettings.setKeyDir(keyDir.toString());
        sshSettings.setConnectTimeout(Duration.ofSeconds(3));
        sshSettings.setAuthTimeout(Duration.ofSeconds(3));
        sshSettings.setCommandTimeout(Duration.ofSeconds(3));
        sshSettings.setSftpTimeout(Duration.ofSeconds(3));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(true);
            server = null;
        }
    }

    @Test
    void bothAcceptTheSameVerifiedTarget() throws Exception {
        writeClientKey(KEY_ALIAS, clientKey);
        startServer(clientKey, new KeyPair[]{hostKey});
        NexaNodeSshTarget target = target(hostKey, KEY_ALIAS, SshHostKeyAlgorithm.RSA);
        stubTarget(target);

        assertEquals(SshTestResultCode.CONNECTED.name(), phaseOneVerdict());

        RunnerPackageSshClient.SshConnection connection = phaseTwoConnect(target);
        assertNotNull(connection.session());
        assertTrue(connection.session().isOpen());
        connection.close();
    }

    @Test
    void bothRejectWrongHostKeyFingerprint() throws Exception {
        writeClientKey(KEY_ALIAS, clientKey);
        startServer(clientKey, new KeyPair[]{hostKey});
        NexaNodeSshTarget target = target(rsaKeyPair(), KEY_ALIAS, SshHostKeyAlgorithm.RSA);
        stubTarget(target);

        assertEquals(SshTestResultCode.HOST_KEY_MISMATCH.name(), phaseOneVerdict());
        assertEquals(SshTestResultCode.HOST_KEY_MISMATCH, phaseTwoFailure(target).code());
    }

    @Test
    void bothRejectAlgorithmTheTargetDoesNotOffer() throws Exception {
        writeClientKey(KEY_ALIAS, clientKey);
        startServer(clientKey, new KeyPair[]{rsaKeyPair()}); // 只提供 RSA
        NexaNodeSshTarget target = target(rsaKeyPair(), KEY_ALIAS, SshHostKeyAlgorithm.ED25519);
        target.setHostKeySha256(fingerprint(rsaKeyPair()));
        stubTarget(target);

        assertEquals(SshTestResultCode.HOST_KEY_ALGORITHM_UNAVAILABLE.name(), phaseOneVerdict());
        assertEquals(SshTestResultCode.HOST_KEY_ALGORITHM_UNAVAILABLE, phaseTwoFailure(target).code());
    }

    @Test
    void bothRejectMissingPrivateKey() throws Exception {
        startServer(clientKey, new KeyPair[]{hostKey});
        NexaNodeSshTarget target = target(hostKey, KEY_ALIAS, SshHostKeyAlgorithm.RSA);
        stubTarget(target);

        assertEquals(SshTestResultCode.KEY_NOT_FOUND.name(), phaseOneVerdict());
        assertEquals(SshTestResultCode.KEY_NOT_FOUND, phaseTwoFailure(target).code());
    }

    @Test
    void bothRejectAliasEscapingKeyDirectory() throws Exception {
        startServer(clientKey, new KeyPair[]{hostKey});
        NexaNodeSshTarget target = target(hostKey, "../../etc/passwd", SshHostKeyAlgorithm.RSA);
        stubTarget(target);

        assertEquals(SshTestResultCode.KEY_ALIAS_INVALID.name(), phaseOneVerdict());
        assertEquals(SshTestResultCode.KEY_ALIAS_INVALID, phaseTwoFailure(target).code());
    }

    @Test
    void bothRejectWhenHostKeyAlgorithmIsNotConfigured() throws Exception {
        writeClientKey(KEY_ALIAS, clientKey);
        startServer(clientKey, new KeyPair[]{hostKey});
        NexaNodeSshTarget target = target(hostKey, KEY_ALIAS, null);
        stubTarget(target);

        assertEquals(SshTestResultCode.HOST_KEY_ALGORITHM_REQUIRED.name(), phaseOneVerdict());
        assertEquals(SshTestResultCode.HOST_KEY_ALGORITHM_REQUIRED, phaseTwoFailure(target).code());
    }

    // ==================== 两侧入口 ====================

    private String phaseOneVerdict() {
        SshTestResultVO result = new NodeSshService(nodeMapper, targetMapper,
                new NodeSshTestRegistry(targetMapper), sshSettings).test(RUNNER_ID);
        return result.getResultCode();
    }

    private RunnerPackageSshClient.SshConnection phaseTwoConnect(NexaNodeSshTarget target) {
        return new RunnerPackageSshClient(sshSettings).connect(target);
    }

    private RunnerPackageSshClient.SshConnectionException phaseTwoFailure(NexaNodeSshTarget target) {
        return assertThrows(RunnerPackageSshClient.SshConnectionException.class,
                () -> phaseTwoConnect(target));
    }

    // ==================== 工具 ====================

    private void stubTarget(NexaNodeSshTarget target) {
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(target);
    }

    private NexaNodeSshTarget target(KeyPair configuredHostKey, String keyAlias, SshHostKeyAlgorithm algorithm) {
        NexaNodeSshTarget target = new NexaNodeSshTarget();
        target.setRunnerId(RUNNER_ID);
        target.setHost("127.0.0.1");
        target.setPort(server.getPort());
        target.setUsername("root");
        target.setKeyAlias(keyAlias);
        target.setHostKeySha256(fingerprint(configuredHostKey));
        target.setHostKeyAlgorithm(algorithm == null ? null : algorithm.name());
        return target;
    }

    private String fingerprint(KeyPair keyPair) {
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, keyPair.getPublic());
    }

    private void startServer(KeyPair acceptedClientKey, KeyPair[] hostKeys) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(freePort());
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKeys));
        server.setPublickeyAuthenticator(acceptOnly(acceptedClientKey));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setCommandFactory((channel, command) -> exitZeroCommand());
        server.start();
    }

    private PublickeyAuthenticator acceptOnly(KeyPair allowed) {
        byte[] allowedEncoded = allowed.getPublic().getEncoded();
        return (username, key, session) -> Arrays.equals(allowedEncoded, key.getEncoded());
    }

    private Command exitZeroCommand() {
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
                callback.onExit(0);
            }

            @Override
            public void destroy(ChannelSession channel) {
            }
        };
    }

    private void writeClientKey(String alias, KeyPair keyPair) throws Exception {
        Path file = keyDir.resolve(alias);
        try (OutputStream out = Files.newOutputStream(file)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, alias, null, out);
        }
        if (Files.getFileStore(keyDir).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        }
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
