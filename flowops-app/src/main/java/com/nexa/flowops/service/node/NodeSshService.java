package com.nexa.flowops.service.node;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexa.flowops.dto.SshTargetRequest;
import com.nexa.flowops.dto.SshTargetVO;
import com.nexa.flowops.dto.SshTestResultVO;
import com.nexa.flowops.entity.NexaNodeSshTarget;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.mapper.NexaNodeSshTargetMapper;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 节点宿主机的 SSH 目标设置与连接验证（阶段 1，S1 契约见 docs/2026-09-27-node-ssh-connection-plan.md）。
 *
 * <p>能力边界：
 * <ul>
 *   <li>严格主机密钥校验：逐次比对指纹，不一致立即中止；不使用自动信任、不读 known_hosts。</li>
 *   <li>全程只读：执行 {@code true} 并打开一次 SFTP 通道，不写远端文件、不安装执行器、不改注册 token。</li>
 *   <li>私钥只按受限别名从 {@code flowops.ssh.key-dir} 读取，别名白名单 + 目录归一化双重防穿越。</li>
 *   <li>连接、认证、只读命令、SFTP 各有独立有界超时，测试不会长时间挂住请求线程。</li>
 *   <li>结果只以 {@link SshTestResultCode} 与固定中文文案返回，不含私钥、路径、远端输出与异常栈。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NodeSshService {

    private static final Logger log = LoggerFactory.getLogger(NodeSshService.class);

    /** 主机名 / IPv4 / IPv6 字面量（不含 scheme 与端口） */
    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,253}$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** 私钥别名：只允许字母数字与 . _ -（无路径分隔符） */
    private static final Pattern KEY_ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    /** 归一化后的 SHA256 指纹：43 字符无填充 base64（32 字节） */
    private static final Pattern SHA256_BASE64_PATTERN = Pattern.compile("^[A-Za-z0-9+/]{43}$");
    private static final String SHA256_PREFIX = "SHA256:";
    private static final int SHA256_BYTES = 32;

    private final NexaNodeMapper nodeMapper;
    private final NexaNodeSshTargetMapper targetMapper;
    private final NodeSshTestRegistry testRegistry;
    private final SshSettings settings;

    // ==================== 读取 / 保存 ====================

    /** 读取目标设置；节点已登记但未配置时返回 null（契约：200 + data=null）。 */
    public SshTargetVO get(String runnerId) {
        requireRegistered(runnerId);
        NexaNodeSshTarget target = targetMapper.selectById(runnerId);
        return target == null ? null : toVO(target);
    }

    /**
     * 全量覆盖保存：递增配置版本、清空测试序号与上一次测试结果。
     *
     * <p>R1：版本递增使所有在途测试自动过期（它们的写回会因版本不匹配被拒绝），
     * 清空旧结果避免页面把未验证的新设置显示成上一次的连接结论。
     */
    @Transactional
    public SshTargetVO save(String runnerId, SshTargetRequest req) {
        requireRegistered(runnerId);
        NexaNodeSshTarget target = validateAndBuild(runnerId, req);

        if (targetMapper.selectById(runnerId) == null) {
            target.setConfigVersion(0L);
            target.setLatestTestSeq(0L);
            targetMapper.insert(target);
        } else {
            targetMapper.update(null, Wrappers.<NexaNodeSshTarget>lambdaUpdate()
                    .eq(NexaNodeSshTarget::getRunnerId, runnerId)
                    .set(NexaNodeSshTarget::getHost, target.getHost())
                    .set(NexaNodeSshTarget::getPort, target.getPort())
                    .set(NexaNodeSshTarget::getUsername, target.getUsername())
                    .set(NexaNodeSshTarget::getKeyAlias, target.getKeyAlias())
                    .set(NexaNodeSshTarget::getHostKeySha256, target.getHostKeySha256())
                    .set(NexaNodeSshTarget::getHostKeyAlgorithm, target.getHostKeyAlgorithm())
                    // 版本用 SQL 自增，避免基于陈旧读值写回而丢更新
                    .setSql("config_version = config_version + 1")
                    .set(NexaNodeSshTarget::getLatestTestSeq, 0L)
                    .set(NexaNodeSshTarget::getLastResultCode, null)
                    .set(NexaNodeSshTarget::getLastResultMessage, null)
                    .set(NexaNodeSshTarget::getLastTestedAt, null)
                    .set(NexaNodeSshTarget::getLastDurationMs, null)
                    .set(NexaNodeSshTarget::getUpdateTime, LocalDateTime.now()));
        }
        return toVO(targetMapper.selectById(runnerId));
    }

    // ==================== 连接测试 ====================

    /**
     * 按已保存的设置执行一次连接验证并落库结果。
     *
     * <p>接口调用本身失败（未配置、节点未登记、无权限）走异常；连接是否成功由返回的结果码表达。
     *
     * <p>R1：探测只读"测试开始时的设置快照"，结果只在配置版本与测试序号都未变化时写回；
     * 否则返回 {@code TEST_OBSOLETE} 且不写入 lastTest。
     */
    public SshTestResultVO test(String runnerId) {
        requireRegistered(runnerId);
        NodeSshTestRegistry.TestSnapshot snapshot = testRegistry.begin(runnerId);

        long startedAt = System.currentTimeMillis();
        SshTestResultCode code;
        try {
            if (SshHostKeyAlgorithm.fromName(snapshot.hostKeyAlgorithm()) == null) {
                // H1.2：旧记录没有算法时不允许继续测试，也不静默假定为某种算法
                code = SshTestResultCode.HOST_KEY_ALGORITHM_REQUIRED;
            } else {
                KeyPair identity = loadIdentity(snapshot.keyAlias());
                code = probe(snapshot, identity);
            }
        } catch (SshProbeException e) {
            code = e.code();
        } catch (Exception e) {
            // 兜底：异常栈只留在服务端日志，不进入 API 响应
            log.warn("[SSH] 连接测试出现未预期错误 runnerId={} host={}:{}",
                    runnerId, snapshot.host(), snapshot.port(), e);
            code = SshTestResultCode.INTERNAL_ERROR;
        }

        long durationMs = System.currentTimeMillis() - startedAt;
        LocalDateTime testedAt = LocalDateTime.now();
        boolean persisted = testRegistry.complete(runnerId, snapshot, code, durationMs, testedAt);
        if (!persisted) {
            log.info("[SSH] 测试结果已过期，未写入 lastTest runnerId={} 版本={} 序号={} 结果={}",
                    runnerId, snapshot.configVersion(), snapshot.testSeq(), code.name());
            return new SshTestResultVO(SshTestResultCode.TEST_OBSOLETE.name(),
                    SshTestResultCode.TEST_OBSOLETE.message(), testedAt, durationMs);
        }
        return new SshTestResultVO(code.name(), code.message(), testedAt, durationMs);
    }

    /**
     * 依次验证：所选算法可用性 → SSH 握手（含主机密钥算法与指纹）→ 公钥认证 → 只读命令 true → SFTP 通道。
     *
     * <p>H1.3：先只用所选算法协商一次判断服务端是否提供它（否则返回
     * {@link SshTestResultCode#HOST_KEY_ALGORITHM_UNAVAILABLE}），再做完整校验；
     * 完整校验的 verifier 同时核对协商到的算法家族与 SHA-256 指纹。
     */
    private SshTestResultCode probe(NodeSshTestRegistry.TestSnapshot target, KeyPair identity) {
        SshHostKeyAlgorithm chosenAlgorithm = SshHostKeyAlgorithm.fromName(target.hostKeyAlgorithm());
        if (chosenAlgorithm == null) {
            throw new SshProbeException(SshTestResultCode.HOST_KEY_ALGORITHM_REQUIRED);
        }
        SshTestResultCode precheckFailure = precheckChosenAlgorithm(target, chosenAlgorithm);
        if (precheckFailure != null) {
            return precheckFailure;
        }
        return probeWithStrictHostKey(target, chosenAlgorithm, identity);
    }

    /**
     * 阶段一：把客户端可协商的主机密钥算法限制为所选算法，探测服务端是否提供它。
     *
     * <p>本阶段只关心"主机密钥是否被送达"，不做指纹比对、不完成认证。
     *
     * @return {@code null} 表示所选算法可用；否则返回对应的失败结果码
     */
    private SshTestResultCode precheckChosenAlgorithm(NodeSshTestRegistry.TestSnapshot target,
                                                      SshHostKeyAlgorithm algorithm) {
        AtomicBoolean hostKeyReceived = new AtomicBoolean(false);
        SshClient client = SshClient.setUpDefaultClient();
        client.setSignatureFactories(algorithm.signatureFactories());
        client.setServerKeyVerifier((session, remoteAddress, serverKey) -> {
            hostKeyReceived.set(true);
            return true;
        });
        client.start();
        try {
            ConnectFuture connect = client.connect(target.username(), target.host(), target.port());
            try {
                connect.verify(settings.getConnectTimeout().toMillis());
            } catch (Exception e) {
                return connect.isDone() ? SshTestResultCode.CONNECT_FAILED : SshTestResultCode.CONNECT_TIMEOUT;
            }

            ClientSession session = connect.getSession();
            try {
                // 主机密钥在密钥交换阶段送达，认证结果与本阶段无关
                session.auth().verify(settings.getAuthTimeout().toMillis());
            } catch (Exception ignored) {
                // 忽略认证结果，只依据是否收到主机密钥判定
            }
            if (hostKeyReceived.get()) {
                return null;
            }
            // 已换到服务端 SSH 标识说明对端在正常应答，此时收不到主机密钥即"没有共同的主机密钥算法"；
            // 连标识都没收到则属于握手未完成，按连接超时处理
            if (session.getServerVersion() != null) {
                log.info("[SSH] 服务端未提供所选主机密钥算法 目标={}:{} 所选算法={}",
                        target.host(), target.port(), algorithm);
                return SshTestResultCode.HOST_KEY_ALGORITHM_UNAVAILABLE;
            }
            return SshTestResultCode.CONNECT_TIMEOUT;
        } catch (Exception e) {
            log.info("[SSH] 所选主机密钥算法预检失败 目标={}:{} 所选算法={} 原因={}",
                    target.host(), target.port(), algorithm, e.getClass().getSimpleName());
            return SshTestResultCode.CONNECT_FAILED;
        } finally {
            try {
                client.stop();
            } catch (Exception e) {
                log.debug("[SSH] 关闭协商探测客户端异常（忽略）: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** 阶段二：完整校验。客户端优先提议所选算法，verifier 同时核对算法家族与指纹。 */
    private SshTestResultCode probeWithStrictHostKey(NodeSshTestRegistry.TestSnapshot target,
                                                     SshHostKeyAlgorithm chosenAlgorithm,
                                                     KeyPair identity) {
        String expectedFingerprint = target.hostKeySha256();
        String testId = "v" + target.configVersion() + "s" + target.testSeq();
        AtomicBoolean hostKeyRejected = new AtomicBoolean(false);
        // SSH 握手中的密钥交换是否已发生：用于区分"握手没走完"与"认证阶段超时"
        AtomicBoolean keyExchangeReached = new AtomicBoolean(false);

        SshClient client = SshClient.setUpDefaultClient();
        // SSHD 只有一套签名工厂（主机密钥协商与登录认证共用）：所选算法放最前以保证优先协商，
        // 同时保留登录密钥算法的工厂以免认证失败；协商到其它算法时由 verifier 拒绝。
        client.setSignatureFactories(hostKeyNegotiationFactories(chosenAlgorithm, identity));
        client.setServerKeyVerifier((session, remoteAddress, serverKey) -> {
            keyExchangeReached.set(true);
            String keyType = KeyUtils.getKeyType(serverKey);
            String negotiated = serverKey.getAlgorithm() + "/" + serverKey.getClass().getSimpleName();
            String observedFingerprint = fingerprintOrUnknown(serverKey);
            boolean algorithmMatched = chosenAlgorithm.matchesKeyType(keyType);
            boolean fingerprintMatched = expectedFingerprint.equalsIgnoreCase(observedFingerprint);
            if (algorithmMatched && fingerprintMatched) {
                log.info("[SSH] 主机密钥校验通过 testId={} 目标={}:{} 所选算法={} 密钥类型={} 指纹={}",
                        testId, target.host(), target.port(), chosenAlgorithm, keyType, observedFingerprint);
            } else {
                hostKeyRejected.set(true);
                log.warn("[SSH] 主机密钥校验失败 testId={} 目标={}:{} 所选算法={} 协商算法={} 密钥类型={} "
                                + "期望指纹={} 观察指纹={} 原因={}（观察值仅供运维比对，不得直接回填）",
                        testId, target.host(), target.port(), chosenAlgorithm, negotiated, keyType,
                        expectedFingerprint, observedFingerprint,
                        algorithmMatched ? "指纹不一致" : "算法不一致");
            }
            // 算法或指纹任一不符即拒绝；无法计算指纹的密钥类型同样拒绝（fail closed）
            return algorithmMatched && fingerprintMatched;
        });
        client.start();

        try {
            ConnectFuture connect = client.connect(target.username(), target.host(), target.port());
            try {
                connect.verify(settings.getConnectTimeout().toMillis());
            } catch (Exception e) {
                if (hostKeyRejected.get()) {
                    throw new SshProbeException(SshTestResultCode.HOST_KEY_MISMATCH);
                }
                if (!connect.isDone()) {
                    throw new SshProbeException(SshTestResultCode.CONNECT_TIMEOUT);
                }
                throw new SshProbeException(SshTestResultCode.CONNECT_FAILED);
            }
            if (hostKeyRejected.get()) {
                throw new SshProbeException(SshTestResultCode.HOST_KEY_MISMATCH);
            }

            ClientSession session = connect.getSession();
            session.addPublicKeyIdentity(identity);

            // 注意：SSHD 的 ConnectFuture 在 TCP 层即完成，SSH 握手（含主机密钥校验）实际发生在认证阶段，
            // 因此主机密钥不匹配与握手超时都必须在这里判定。
            AuthFuture auth = session.auth();
            try {
                auth.verify(settings.getAuthTimeout().toMillis());
            } catch (Exception e) {
                if (hostKeyRejected.get()) {
                    throw new SshProbeException(SshTestResultCode.HOST_KEY_MISMATCH);
                }
                if (!auth.isDone()) {
                    throw new SshProbeException(keyExchangeReached.get()
                            ? SshTestResultCode.AUTH_TIMEOUT
                            : SshTestResultCode.CONNECT_TIMEOUT);
                }
                // 没走到主机密钥校验就失败：算法在两次握手之间变得不可用或协议无法协商
                throw new SshProbeException(keyExchangeReached.get()
                        ? SshTestResultCode.AUTH_FAILED
                        : SshTestResultCode.HOST_KEY_ALGORITHM_UNAVAILABLE);
            }

            runReadOnlyTrue(session);
            openSftpChannel(session);
            return SshTestResultCode.CONNECTED;
        } catch (SshProbeException e) {
            throw e;
        } catch (Exception e) {
            // 握手/会话建立阶段的非预期 IO 故障：只回结果码，服务端日志记录类别
            log.warn("[SSH] 探测过程异常 host={}:{} 原因={}",
                    target.host(), target.port(), e.getClass().getSimpleName());
            throw new SshProbeException(SshTestResultCode.INTERNAL_ERROR);
        } finally {
            try {
                client.stop();
            } catch (Exception e) {
                log.debug("[SSH] 关闭客户端时出现异常（忽略）: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** 只读命令 true：只判断退出码，不回传任何远端输出。 */
    private void runReadOnlyTrue(ClientSession session) {
        long timeoutMs = settings.getCommandTimeout().toMillis();
        try (ChannelExec channel = session.createExecChannel("true")) {
            channel.setOut(new ByteArrayOutputStream());
            channel.setErr(new ByteArrayOutputStream());
            channel.open().verify(timeoutMs);
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeoutMs);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new SshProbeException(SshTestResultCode.COMMAND_TIMEOUT);
            }
            Integer exitStatus = channel.getExitStatus();
            if (exitStatus == null || exitStatus != 0) {
                throw new SshProbeException(SshTestResultCode.COMMAND_FAILED);
            }
        } catch (SshProbeException e) {
            throw e;
        } catch (Exception e) {
            throw new SshProbeException(SshTestResultCode.COMMAND_FAILED);
        }
    }

    /** 打开一次 SFTP 通道后立即关闭：只验证通道可用，不做任何远端读写。 */
    private void openSftpChannel(ClientSession session) {
        long timeoutMs = settings.getSftpTimeout().toMillis();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ssh-sftp-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<SftpClient> opening = executor.submit(() -> SftpClientFactory.instance().createSftpClient(session));
            try (SftpClient ignored = opening.get(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.debug("[SSH] SFTP 通道打开成功");
            }
        } catch (TimeoutException e) {
            throw new SshProbeException(SshTestResultCode.SFTP_TIMEOUT);
        } catch (ExecutionException e) {
            throw new SshProbeException(SshTestResultCode.SFTP_FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshProbeException(SshTestResultCode.SFTP_FAILED);
        } catch (Exception e) {
            throw new SshProbeException(SshTestResultCode.SFTP_FAILED);
        } finally {
            executor.shutdownNow();
        }
    }

    // ==================== 私钥 ====================

    /**
     * 按受限别名加载私钥；无口令私钥，带口令按 KEY_UNREADABLE 处理。
     *
     * <p>R3：存在性与读取都走 {@link #verifiedKeyFile(String)}（拒绝符号链接 + 真实路径边界），
     * 并以 NOFOLLOW 打开，避免校验后被替换为链接。
     */
    private KeyPair loadIdentity(String keyAlias) {
        Path keyFile = verifiedKeyFile(keyAlias);
        if (isPermissionTooOpen(keyFile)) {
            throw new SshProbeException(SshTestResultCode.KEY_PERMISSION_TOO_OPEN);
        }
        try (InputStream in = Files.newInputStream(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            for (KeyPair pair : SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(keyAlias), in,
                    FilePasswordProvider.EMPTY)) {
                return pair;
            }
            throw new SshProbeException(SshTestResultCode.KEY_UNREADABLE);
        } catch (SshProbeException e) {
            throw e;
        } catch (Exception e) {
            // 只记录类别，不记录密钥内容或文件内容
            log.warn("[SSH] 私钥加载失败 alias={} 原因={}", keyAlias, e.getClass().getSimpleName());
            throw new SshProbeException(SshTestResultCode.KEY_UNREADABLE);
        }
    }

    /**
     * 别名白名单 + 目录归一化 + 拒绝符号链接。
     *
     * <p>R3：别名自身若是指向别处的符号链接，一律按非法处理——目录边界不能只靠字符串规范化来保证。
     */
    private Path resolveKeyFile(String keyAlias) {
        if (keyAlias == null || !KEY_ALIAS_PATTERN.matcher(keyAlias).matches()
                || ".".equals(keyAlias) || "..".equals(keyAlias)) {
            throw new SshProbeException(SshTestResultCode.KEY_ALIAS_INVALID);
        }
        Path base = Paths.get(settings.getKeyDir()).toAbsolutePath().normalize();
        Path resolved = base.resolve(keyAlias).normalize();
        if (resolved.equals(base) || !resolved.startsWith(base)) {
            throw new SshProbeException(SshTestResultCode.KEY_ALIAS_INVALID);
        }
        if (isSymlink(resolved)) {
            throw new SshProbeException(SshTestResultCode.KEY_ALIAS_INVALID);
        }
        return resolved;
    }

    /**
     * 私钥路径的最终判定：存在性、真实路径边界与普通文件校验，读取与 {@code keyFileExists} 共用同一规则。
     *
     * <p>R3：即使别名不是链接，也按<b>真实路径</b>确认它落在真实的 key-dir 内（key-dir 自身可能是链接），
     * 并只接受普通文件；任何一步不满足都不可读。
     */
    private Path verifiedKeyFile(String keyAlias) {
        Path candidate = resolveKeyFile(keyAlias);
        Path realBase;
        Path realFile;
        try {
            realBase = Paths.get(settings.getKeyDir()).toAbsolutePath().normalize().toRealPath();
            realFile = candidate.toRealPath();
        } catch (IOException e) {
            // key-dir 不存在或别名文件不存在
            throw new SshProbeException(SshTestResultCode.KEY_NOT_FOUND);
        }
        if (!realFile.startsWith(realBase)) {
            throw new SshProbeException(SshTestResultCode.KEY_ALIAS_INVALID);
        }
        if (!Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new SshProbeException(SshTestResultCode.KEY_NOT_FOUND);
        }
        return realFile;
    }

    /** 符号链接判定抽成可覆盖方法，便于在无法创建链接的平台上测试该分支。 */
    boolean isSymlink(Path path) {
        return Files.isSymbolicLink(path);
    }

    /**
     * 主机密钥协商用的签名工厂：所选算法排在最前（优先协商），随后补充登录密钥算法所需的工厂，
     * 避免因工厂缺失导致认证失败。协商到所选算法之外的密钥时由 verifier 拒绝。
     */
    private List<NamedFactory<Signature>> hostKeyNegotiationFactories(SshHostKeyAlgorithm chosen, KeyPair identity) {
        List<NamedFactory<Signature>> factories = new ArrayList<>(chosen.signatureFactories());
        if (identity == null) {
            return factories;
        }
        String loginKeyType = KeyUtils.getKeyType(identity.getPublic());
        for (BuiltinSignatures candidate : BuiltinSignatures.values()) {
            if (!factories.contains(candidate) && sameKeyType(candidate.getName(), loginKeyType)) {
                factories.add(candidate);
            }
        }
        return factories;
    }

    private boolean sameKeyType(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return KeyUtils.getCanonicalKeyType(left).equals(KeyUtils.getCanonicalKeyType(right));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 计算服务器主机密钥的 SHA-256 指纹；无法编码的密钥类型返回固定占位符（调用方按不匹配处理）。
     *
     * <p>只用于日志与比对，不返回公钥内容。
     */
    private String fingerprintOrUnknown(PublicKey serverKey) {
        try {
            return KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
        } catch (RuntimeException e) {
            return "<该密钥类型无法计算指纹:" + serverKey.getAlgorithm() + ">";
        }
    }

    /**
     * 私钥权限校验：group/other 任何权限位都算过宽（要求 0600 或更严）。
     *
     * <p>非 POSIX 文件系统（如本地 Windows 开发机）无法判定，跳过校验——生产为主节点 Linux 容器。
     */
    boolean isPermissionTooOpen(Path keyFile) {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(keyFile);
        } catch (UnsupportedOperationException e) {
            return false;
        } catch (Exception e) {
            throw new SshProbeException(SshTestResultCode.KEY_UNREADABLE);
        }
        return permissions.stream().anyMatch(permission -> switch (permission) {
            case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
            default -> false;
        });
    }

    // ==================== 校验 ====================

    NexaNodeSshTarget validateAndBuild(String runnerId, SshTargetRequest req) {
        if (req == null) {
            throw SshTargetException.invalid("请求体不能为空");
        }
        String host = req.getHost() == null ? null : req.getHost().trim();
        if (host == null || host.isEmpty() || host.contains("://") || !HOST_PATTERN.matcher(host).matches()) {
            throw SshTargetException.invalid("host 不合法（只填主机名或 IP，不含协议与端口）");
        }
        int port = req.getPort() == null ? 22 : req.getPort();
        if (port < 1 || port > 65535) {
            throw SshTargetException.invalid("port 必须在 1-65535 之间");
        }
        String username = req.getUsername() == null ? null : req.getUsername().trim();
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw SshTargetException.invalid("username 不合法（只允许字母数字与 . _ -）");
        }
        String keyAlias = req.getKeyAlias() == null ? null : req.getKeyAlias().trim();
        if (keyAlias == null || !KEY_ALIAS_PATTERN.matcher(keyAlias).matches()
                || ".".equals(keyAlias) || "..".equals(keyAlias)) {
            throw SshTargetException.invalid("keyAlias 不合法（只允许字母数字与 . _ -，且不能是 . 或 ..）");
        }
        // H1.2：算法必填，与指纹成对；不接受空值，避免静默假定
        SshHostKeyAlgorithm algorithm = SshHostKeyAlgorithm.fromName(req.getHostKeyAlgorithm());
        if (algorithm == null) {
            throw SshTargetException.invalid("hostKeyAlgorithm 必须是 " + String.join(" / ", SshHostKeyAlgorithm.names()));
        }

        NexaNodeSshTarget target = new NexaNodeSshTarget();
        target.setRunnerId(runnerId);
        target.setHost(host);
        target.setPort(port);
        target.setUsername(username);
        target.setKeyAlias(keyAlias);
        target.setHostKeySha256(normalizeFingerprint(req.getHostKeySha256()));
        target.setHostKeyAlgorithm(algorithm.name());
        return target;
    }

    /** 归一化主机密钥指纹：去前缀、去填充，校验为 32 字节 base64，统一存成 SHA256:&lt;43 字符&gt;。 */
    static String normalizeFingerprint(String raw) {
        if (raw == null || raw.isBlank()) {
            throw SshTargetException.invalid("hostKeySha256 不能为空");
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, SHA256_PREFIX, 0, SHA256_PREFIX.length())) {
            value = value.substring(SHA256_PREFIX.length()).trim();
        }
        value = value.replace("=", "").trim();
        if (!SHA256_BASE64_PATTERN.matcher(value).matches()) {
            throw SshTargetException.invalid("hostKeySha256 格式应为 SHA256:<base64>");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw SshTargetException.invalid("hostKeySha256 不是合法的 base64");
        }
        if (decoded.length != SHA256_BYTES) {
            throw SshTargetException.invalid("hostKeySha256 解码后必须为 32 字节");
        }
        return SHA256_PREFIX + value;
    }

    // ==================== 内部工具 ====================

    private void requireRegistered(String runnerId) {
        if (runnerId == null || runnerId.isBlank() || nodeMapper.selectById(runnerId) == null) {
            throw SshTargetException.notRegistered(runnerId);
        }
    }

    private SshTargetVO toVO(NexaNodeSshTarget target) {
        SshTargetVO vo = new SshTargetVO();
        vo.setRunnerId(target.getRunnerId());
        vo.setHost(target.getHost());
        vo.setPort(target.getPort());
        vo.setUsername(target.getUsername());
        vo.setKeyAlias(target.getKeyAlias());
        vo.setHostKeySha256(target.getHostKeySha256());
        vo.setHostKeyAlgorithm(target.getHostKeyAlgorithm());
        vo.setKeyFileExists(keyFileExists(target.getKeyAlias()));
        if (target.getLastResultCode() != null) {
            SshTestResultCode code = SshTestResultCode.fromName(target.getLastResultCode());
            vo.setLastTest(new SshTestResultVO(code.name(), code.message(),
                    target.getLastTestedAt(), target.getLastDurationMs()));
        }
        vo.setUpdateTime(target.getUpdateTime());
        return vo;
    }

    /**
     * 只返回"可用私钥是否存在"，不返回路径。
     *
     * <p>R3：与读取使用完全相同规则（{@link #verifiedKeyFile(String)}：别名白名单、拒绝符号链接、
     * 真实路径须在真实 key-dir 内、必须是普通文件）；任何不满足都按"不存在"处理。
     */
    private boolean keyFileExists(String keyAlias) {
        try {
            return Files.isRegularFile(verifiedKeyFile(keyAlias), LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 探测过程内部的失败载体：只携带结果码，不携带远端输出或异常信息。 */
    private static final class SshProbeException extends RuntimeException {
        private final SshTestResultCode code;

        SshProbeException(SshTestResultCode code) {
            super(code.name());
            this.code = code;
        }

        SshTestResultCode code() {
            return code;
        }
    }
}
