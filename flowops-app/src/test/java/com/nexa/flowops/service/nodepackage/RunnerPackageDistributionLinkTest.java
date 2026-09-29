package com.nexa.flowops.service.nodepackage;

import com.nexa.flowops.dto.PackageDistributionVO;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.entity.NexaNodePackage;
import com.nexa.flowops.entity.NexaNodePackageDistribution;
import com.nexa.flowops.entity.NexaNodeSshTarget;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.mapper.NexaNodePackageDistributionMapper;
import com.nexa.flowops.mapper.NexaNodePackageMapper;
import com.nexa.flowops.mapper.NexaNodeSshTargetMapper;
import com.nexa.flowops.service.node.SshHostKeyAlgorithm;
import com.nexa.flowops.service.node.SshSettings;
import com.nexa.flowops.service.node.SshTestResultCode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发布包分发真实链路测试（阶段 2 P3）：进程内 Apache MINA SSHD 服务器（真实 SSH + SFTP + 受控远端命令），
 * 主节点侧跑**真实**的 {@link RunnerPackageSshClient}、{@link RunnerPackageDistributor} 与
 * {@link LocalRunnerPackageStore}，覆盖成功、已存在、摘要不符、目录缺失、缺命令、空间不足，
 * 并断言"只投递包、不写凭据、不启动任何进程"。
 *
 * <p>被分发的包是执行器仓库真实产出的格式 v1 包（见 {@code src/test/resources/runner-package/README.md}），
 * 因此链路两端（包格式与传输）都是真实实现，只有远端文件系统与命令是测试替身。
 */
class RunnerPackageDistributionLinkTest {

    private static final String RUNNER_ID = "runner-1";
    private static final String KEY_ALIAS = "runner-1-key";
    private static final String REMOTE_DIR = "/opt/flowops/runner/packages";
    private static final String FIXTURE = "runner-package/flowops-executor-0.7.0-linux-amd64.tar.gz";

    @TempDir
    Path work;

    private SshServer server;
    private KeyPair hostKey;
    private KeyPair clientKey;
    private Path keyDir;
    private Path storeDir;
    private Path remoteRoot;

    private final List<String> commands = Collections.synchronizedList(new ArrayList<>());
    private volatile long dfAvailableKb = 1024L * 1024L;
    private volatile boolean checksumToolMissing;
    private volatile boolean tamperChecksum;

    private RunnerPackageDistributor distributor;
    private LocalRunnerPackageStore store;
    private RunnerPackageSettings packageSettings;
    private NexaNodeSshTarget target;
    private String sha256;

    /** 服务流程替身用：模拟库表的分发记录与 update wrapper */
    private final java.util.Map<Long, NexaNodePackageDistribution> serviceRecords =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Wrapper<NexaNodePackageDistribution>> serviceUpdates =
            Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        // 纯单测无 Spring 上下文：LambdaUpdateWrapper 需要实体元信息缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NexaNodePackageDistribution.class);
        TableInfoHelper.initTableInfo(assistant, NexaNodePackage.class);

        keyDir = Files.createDirectories(work.resolve("keys"));
        storeDir = Files.createDirectories(work.resolve("store"));
        remoteRoot = Files.createDirectories(work.resolve("remote"));

        hostKey = new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair();
        clientKey = rsaKeyPair();
        writeClientKey(KEY_ALIAS, clientKey);
        startServer();

        SshSettings sshSettings = new SshSettings();
        sshSettings.setKeyDir(keyDir.toString());
        sshSettings.setConnectTimeout(Duration.ofSeconds(5));
        sshSettings.setAuthTimeout(Duration.ofSeconds(5));

        packageSettings = new RunnerPackageSettings();
        packageSettings.setStoreDir(storeDir.toString());
        packageSettings.setRemoteDir(REMOTE_DIR);
        packageSettings.setUploadTimeout(Duration.ofSeconds(30));
        packageSettings.setRemoteCommandTimeout(Duration.ofSeconds(10));

        store = new LocalRunnerPackageStore(packageSettings);
        distributor = new RunnerPackageDistributor(new RunnerPackageSshClient(sshSettings), packageSettings);

        byte[] packageBytes = readFixture();
        sha256 = PackageDigests.sha256Hex(packageBytes);
        Path temp = store.createTempFile();
        Files.write(temp, packageBytes);
        store.commit(temp, sha256);

        target = new NexaNodeSshTarget();
        target.setRunnerId(RUNNER_ID);
        target.setHost("127.0.0.1");
        target.setPort(server.getPort());
        target.setUsername("root");
        target.setKeyAlias(KEY_ALIAS);
        target.setHostKeySha256(KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.getPublic()));
        target.setHostKeyAlgorithm(SshHostKeyAlgorithm.ED25519.name());
        // 阶段 1 的"已通过连接测试"状态：R1 绑定与后台核对都以它为前提
        target.setConfigVersion(0L);
        target.setLastResultCode(SshTestResultCode.CONNECTED.name());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(true);
            server = null;
        }
    }

    // ==================== 成功路径 ====================

    @Test
    void distributesRealPackageAndRenamesAtomically() throws Exception {
        Path packagesDir = createRemoteDir();
        List<PackageDistributionStatus> stages = new ArrayList<>();

        RunnerPackageDistributor.Outcome outcome = distributor.distribute(RUNNER_ID, sha256,
                store.packagePath(sha256), Files.size(store.packagePath(sha256)), target, stages::add);

        assertFalse(outcome.alreadyPresent());
        assertEquals(REMOTE_DIR + "/" + sha256 + ".tar.gz", outcome.remotePath());
        Path finalFile = packagesDir.resolve(sha256 + ".tar.gz");
        assertTrue(Files.isRegularFile(finalFile), "正式包应原子改名到 <sha256>.tar.gz");
        assertArrayEquals(packageBytes(), Files.readAllBytes(finalFile));
        assertFalse(Files.exists(packagesDir.resolve(sha256 + ".part")), "不应残留临时文件");
        assertEquals(List.of(PackageDistributionStatus.UPLOADING, PackageDistributionStatus.VERIFYING), stages);
        assertOnlyAllowedRemoteCommands();
        assertEquals(List.of(finalFile), listRemoteFiles(), "目标机只多出正式包：没有凭据文件、没有解包目录");
    }

    @Test
    void skipsTransferWhenSamePackageAlreadyPresent() throws Exception {
        Path packagesDir = createRemoteDir();
        Path finalFile = packagesDir.resolve(sha256 + ".tar.gz");
        Files.write(finalFile, packageBytes());
        commands.clear();
        List<PackageDistributionStatus> stages = new ArrayList<>();

        RunnerPackageDistributor.Outcome outcome = distributor.distribute(RUNNER_ID, sha256,
                store.packagePath(sha256), Files.size(store.packagePath(sha256)), target, stages::add);

        assertTrue(outcome.alreadyPresent(), "目标机已有同摘要包应视为成功");
        assertEquals(List.of(PackageDistributionStatus.VERIFYING), stages, "已存在时跳过 UPLOADING");
        assertTrue(commands.stream().anyMatch(command -> command.startsWith("sha256sum")),
                "必须远端核对摘要");
        assertTrue(commands.stream().noneMatch(command -> command.startsWith("mv ")),
                "已存在时不得再改名");
        assertArrayEquals(packageBytes(), Files.readAllBytes(finalFile));
    }

    // ==================== 失败路径 ====================

    @Test
    void reportsChecksumMismatchAndRemovesPartFile() throws Exception {
        Path packagesDir = createRemoteDir();
        tamperChecksum = true;

        PackageDistributionFailure failure = assertThrows(PackageDistributionFailure.class,
                () -> distribute());

        assertEquals(PackageDistributionErrorCode.REMOTE_CHECKSUM_MISMATCH.name(), failure.errorCode());
        assertFalse(Files.exists(packagesDir.resolve(sha256 + ".tar.gz")), "校验不符绝不改名为正式包");
        assertFalse(Files.exists(packagesDir.resolve(sha256 + ".part")), "应清理临时文件（SFTP remove）");
        assertTrue(commands.stream().noneMatch(command -> command.startsWith("mv -f")),
                "校验不符不得改名");
    }

    @Test
    void failureKeepsExistingPackageUntouched() throws Exception {
        Path packagesDir = createRemoteDir();
        Path finalFile = packagesDir.resolve(sha256 + ".tar.gz");
        // 目标机已有一份同名但内容不同（长度相同）的文件：既不能被当成"已存在"，也不能被失败路径破坏
        byte[] previous = packageBytes();
        previous[previous.length - 1] ^= 0x01;
        Files.write(finalFile, previous);
        tamperChecksum = true;

        PackageDistributionFailure failure = assertThrows(PackageDistributionFailure.class,
                () -> distribute());

        assertEquals(PackageDistributionErrorCode.REMOTE_CHECKSUM_MISMATCH.name(), failure.errorCode());
        assertArrayEquals(previous, Files.readAllBytes(finalFile), "失败必须保留目标机既有正式包");
        assertFalse(Files.exists(packagesDir.resolve(sha256 + ".part")), "失败必须清理本次临时文件");
    }

    @Test
    void reportsMissingRemoteDirectoryWithoutWritingAnything() throws Exception {
        PackageDistributionFailure failure = assertThrows(PackageDistributionFailure.class,
                () -> distribute());

        assertEquals(PackageDistributionErrorCode.REMOTE_DIR_MISSING.name(), failure.errorCode());
        assertTrue(Files.notExists(remoteRoot.resolve("opt")), "主节点不创建远端目录");
        assertTrue(listRemoteFiles().isEmpty(), "目标机不应有任何新文件");
    }

    @Test
    void reportsMissingRemoteTool() throws Exception {
        Path packagesDir = createRemoteDir();
        checksumToolMissing = true;

        PackageDistributionFailure failure = assertThrows(PackageDistributionFailure.class,
                () -> distribute());

        assertEquals(PackageDistributionErrorCode.REMOTE_TOOL_MISSING.name(), failure.errorCode());
        assertFalse(Files.exists(packagesDir.resolve(sha256 + ".part")), "应清理临时文件");
    }

    @Test
    void refusesTransferWhenRemoteSpaceIsInsufficient() throws Exception {
        Path packagesDir = createRemoteDir();
        dfAvailableKb = 1; // 1 KiB 可用，远小于包大小 + 余量

        PackageDistributionFailure failure = assertThrows(PackageDistributionFailure.class,
                () -> distribute());

        assertEquals(PackageDistributionErrorCode.REMOTE_DISK_INSUFFICIENT.name(), failure.errorCode());
        assertTrue(listRemoteFiles().isEmpty(), "空间不足时不应落任何文件");
        assertTrue(commands.stream().noneMatch(command -> command.startsWith("sha256sum")),
                "空间预检应在传输与校验之前");
        assertNotNull(packagesDir);
    }

    // ==================== R1：绑定版本失效时不得有任何远端动作 ====================

    @Test
    void changedSshConfigAfterQueueingFailsWithoutAnyRemoteWrite() throws Exception {
        createRemoteDir();
        NexaNodeSshTarget liveTarget = target;
        RunnerPackageDistributionService service = serviceWithLiveTarget(liveTarget);

        PackageDistributionVO queued = service.distribute(RUNNER_ID, sha256, "admin");
        assertEquals(Long.valueOf(0L), queued.getSshConfigVersion(), "排队时绑定当时的 config_version");
        // 模拟管理员在排队后覆盖 SSH 设置：PUT /ssh 使 config_version +1 并清空 lastTest
        liveTarget.setConfigVersion(1L);
        liveTarget.setHost("10.0.0.9");
        liveTarget.setKeyAlias("another-key");
        liveTarget.setLastResultCode(null);

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("FAILED"));
        assertTrue(terminal.getParamNameValuePairs().containsValue("SSH_CONFIG_CHANGED"));
        assertTrue(commands.isEmpty(), "失效判定必须发生在建立远端会话之前");
        assertTrue(listRemoteFiles().isEmpty(), "目标机不得出现任何文件");
    }

    @Test
    void unchangedSshConfigDistributesThroughServiceFlow() throws Exception {
        Path packagesDir = createRemoteDir();
        RunnerPackageDistributionService service = serviceWithLiveTarget(target);

        PackageDistributionVO queued = service.distribute(RUNNER_ID, sha256, "admin");
        assertEquals(Long.valueOf(0L), queued.getSshConfigVersion());

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("SUCCEEDED"));
        assertTrue(Files.isRegularFile(packagesDir.resolve(sha256 + ".tar.gz")));
        assertEquals(List.of(packagesDir.resolve(sha256 + ".tar.gz")), listRemoteFiles());
    }

    @Test
    void refusesDistributionWhenStoredSourceDoesNotMatchDigest() throws Exception {
        createRemoteDir();
        // R2：主节点源文件被改坏 → 在建立会话之前失败，不做无谓传输
        Files.write(store.packagePath(sha256), "tampered".getBytes(StandardCharsets.UTF_8));

        PackageDistributionFailure failure = assertThrows(PackageDistributionFailure.class,
                () -> distribute());

        assertEquals(PackageDistributionErrorCode.STORE_CHECKSUM_MISMATCH.name(), failure.errorCode());
        assertTrue(commands.isEmpty(), "本地源校验失败必须发生在建立远端会话之前");
        assertTrue(listRemoteFiles().isEmpty());
    }

    // ==================== 服务流程替身（真实存储/分发器 + mock 持久层） ====================

    /** 用真实 store/distributor/SSH 客户端 + mock 持久层组装分发服务，用于验证 R1 的前后台衔接。 */
    private RunnerPackageDistributionService serviceWithLiveTarget(NexaNodeSshTarget liveTarget) {
        NexaNodeMapper nodeMapper = mock(NexaNodeMapper.class);
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(new NexaNode());
        NexaNodeSshTargetMapper targetMapper = mock(NexaNodeSshTargetMapper.class);
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(liveTarget);

        NexaNodePackage pkg = new NexaNodePackage();
        pkg.setSha256(sha256);
        pkg.setFileName("flowops-executor-0.7.0-linux-amd64.tar.gz");
        pkg.setVersion("0.7.0");
        pkg.setSizeBytes(Files.exists(store.packagePath(sha256)) ? sizeOf(store.packagePath(sha256)) : 993L);
        NexaNodePackageMapper packageMapper = mock(NexaNodePackageMapper.class);
        when(packageMapper.selectById(sha256)).thenReturn(pkg);
        RunnerPackageService packageService = mock(RunnerPackageService.class);
        when(packageService.requireEntity(sha256)).thenReturn(pkg);

        NexaNodePackageDistributionMapper distributionMapper = mock(NexaNodePackageDistributionMapper.class);
        when(distributionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(distributionMapper.insert(any(NexaNodePackageDistribution.class))).thenAnswer(invocation -> {
            NexaNodePackageDistribution record = invocation.getArgument(0);
            record.setId(11L);
            serviceRecords.put(11L, record);
            return 1;
        });
        when(distributionMapper.selectById(any())).thenAnswer(invocation ->
                serviceRecords.get(invocation.getArgument(0, Long.class)));
        when(distributionMapper.update(isNull(), any())).thenAnswer(invocation -> {
            serviceUpdates.add(invocation.getArgument(1));
            return 1;
        });

        return new RunnerPackageDistributionService(nodeMapper, targetMapper, packageMapper,
                distributionMapper, packageService, distributor, packageSettings, store);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 993L;
        }
    }

    /** 等待后台任务写入终态（SUCCEEDED/FAILED），最多 5 秒。 */
    private AbstractWrapper<?, ?, ?> awaitTerminalUpdate() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (Wrapper<NexaNodePackageDistribution> wrapper : List.copyOf(serviceUpdates)) {
                AbstractWrapper<?, ?, ?> candidate = (AbstractWrapper<?, ?, ?>) wrapper;
                var values = candidate.getParamNameValuePairs().values();
                if (values.contains("SUCCEEDED") || values.contains("FAILED")) {
                    return candidate;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("未在 5 秒内写入分发终态");
    }

    // ==================== 断言与替身 ====================

    private RunnerPackageDistributor.Outcome distribute() {
        Path local = store.packagePath(sha256);
        try {
            return distributor.distribute(RUNNER_ID, sha256, local, Files.size(local), target, status -> {
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 只允许固定模板命令：不得出现 systemctl / docker / 执行包内二进制 / 触碰凭据的命令。 */
    private void assertOnlyAllowedRemoteCommands() {
        for (String command : List.copyOf(commands)) {
            assertTrue(command.startsWith("sha256sum -- ") || command.startsWith("df -Pk -- ")
                            || command.startsWith("mv -f -- ") || command.startsWith("rm -f -- "),
                    "不允许的远端命令: " + command);
            assertFalse(command.contains("systemctl") || command.contains("docker")
                            || command.contains("flowops-executor") || command.contains("token")
                            || command.contains("private") || command.contains(".env"),
                    "分发不得安装、启动或写凭据: " + command);
        }
    }

    private List<Path> listRemoteFiles() throws IOException {
        if (Files.notExists(remoteRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(remoteRoot)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private Path createRemoteDir() throws IOException {
        return Files.createDirectories(remoteRoot.resolve(REMOTE_DIR.replaceFirst("^/", "")));
    }

    private byte[] packageBytes() throws IOException {
        return Files.readAllBytes(store.packagePath(sha256));
    }

    private byte[] readFixture() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "缺少测试固件: " + FIXTURE);
            return in.readAllBytes();
        }
    }

    private Path resolveRemote(String sessionPath) {
        return remoteRoot.resolve(sessionPath.replaceFirst("^/", ""));
    }

    private static String quotedArg(String command) {
        int first = command.indexOf('\'');
        int last = command.lastIndexOf('\'');
        return first < 0 || last <= first ? "" : command.substring(first + 1, last);
    }

    // ==================== 内嵌服务器 ====================

    private void startServer() throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(freePort());
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setPublickeyAuthenticator(acceptOnly(clientKey));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        server.setCommandFactory((channel, command) -> remoteCommand(command));
        server.start();
    }

    /** 受控远端命令：只实现分发用到的四条固定命令，其余一律拒绝（并记录）。 */
    private Command remoteCommand(String rawCommand) {
        commands.add(rawCommand);
        return new Command() {
            private ExitCallback callback;
            private OutputStream out = OutputStream.nullOutputStream();

            @Override
            public void setInputStream(InputStream in) {
            }

            @Override
            public void setOutputStream(OutputStream out) {
                this.out = out;
            }

            @Override
            public void setErrorStream(OutputStream err) {
            }

            @Override
            public void setExitCallback(ExitCallback callback) {
                this.callback = callback;
            }

            @Override
            public void start(ChannelSession channel, Environment env) throws IOException {
                int exitStatus = 1;
                String stdout = "";
                try {
                    if (checksumToolMissing && rawCommand.startsWith("sha256sum")) {
                        exitStatus = 127;
                    } else if (rawCommand.startsWith("sha256sum -- ")) {
                        Path file = resolveRemote(quotedArg(rawCommand));
                        if (Files.isRegularFile(file)) {
                            stdout = (tamperChecksum ? "0".repeat(64) : PackageDigests.sha256Hex(file))
                                    + "  " + quotedArg(rawCommand) + "\n";
                            exitStatus = 0;
                        }
                    } else if (rawCommand.startsWith("df -Pk -- ")) {
                        stdout = "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
                                + "overlay 1000000 1000 " + dfAvailableKb + " 1% "
                                + quotedArg(rawCommand) + "\n";
                        exitStatus = 0;
                    } else if (rawCommand.startsWith("mv -f -- ")) {
                        int split = rawCommand.lastIndexOf(" '");
                        Files.move(resolveRemote(quotedArg(rawCommand.substring(0, split))),
                                resolveRemote(quotedArg(rawCommand.substring(split))),
                                StandardCopyOption.REPLACE_EXISTING);
                        exitStatus = 0;
                    } else if (rawCommand.startsWith("rm -f -- ")) {
                        Files.deleteIfExists(resolveRemote(quotedArg(rawCommand)));
                        exitStatus = 0;
                    }
                } catch (Exception e) {
                    exitStatus = 1;
                }
                out.write(stdout.getBytes(StandardCharsets.UTF_8));
                out.flush();
                callback.onExit(exitStatus);
            }

            @Override
            public void destroy(ChannelSession channel) {
            }
        };
    }

    private PublickeyAuthenticator acceptOnly(KeyPair allowed) {
        byte[] allowedEncoded = allowed.getPublic().getEncoded();
        return (username, key, session) -> Arrays.equals(allowedEncoded, key.getEncoded());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
}
