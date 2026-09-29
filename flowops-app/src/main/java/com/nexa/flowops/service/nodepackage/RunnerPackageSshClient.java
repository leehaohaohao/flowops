package com.nexa.flowops.service.nodepackage;

import com.nexa.flowops.entity.NexaNodeSshTarget;
import com.nexa.flowops.service.node.SshHostKeyAlgorithm;
import com.nexa.flowops.service.node.SshSettings;
import com.nexa.flowops.service.node.SshTestResultCode;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.client.SshClient;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 分发用的 SSH 会话工厂（阶段 2 P3）：阶段 1 契约的**同一套信任边界**，供 SFTP 分发放开一条受校验的会话。
 *
 * <p>与 {@link com.nexa.flowops.service.node.NodeSshService} 完全一致的策略：
 * <ul>
 *   <li>所选算法与 SHA-256 指纹**双重校验**，任一不符立即中止（不使用自动信任、不读 known_hosts）；</li>
 *   <li>先只用所选算法协商一次，判定服务端是否提供该算法（否则 {@code HOST_KEY_ALGORITHM_UNAVAILABLE}）；</li>
 *   <li>私钥只按受限别名从 {@code flowops.ssh.key-dir} 读取：别名白名单、拒绝符号链接、真实路径边界、{@code 0600}；</li>
 *   <li>连接/认证超时复用 {@code flowops.ssh.connect-timeout} / {@code auth-timeout}；</li>
 *   <li>失败只以阶段 1 的结果码表达，不含私钥内容、远端输出或异常栈。</li>
 * </ul>
 *
 * <p>该策略与 {@code NodeSshService} 各有一份实现（阶段 1 的行有独立允许范围，本行不改动它）；
 * 二者的一致性由 {@code RunnerPackageSshConsistencyTest} 用同一批真实内嵌服务器/目标/密钥用例交叉断言，
 * 防止两处判定发生漂移。
 */
@Component
@RequiredArgsConstructor
public class RunnerPackageSshClient {

    private static final Logger log = LoggerFactory.getLogger(RunnerPackageSshClient.class);

    /** 私钥别名：只允许字母数字与 . _ -（与阶段 1 相同，无路径分隔符） */
    private static final Pattern KEY_ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private final SshSettings sshSettings;

    /** 建立一条已通过严格主机密钥校验与公钥认证的会话；调用方负责关闭。 */
    public SshConnection connect(NexaNodeSshTarget target) {
        SshHostKeyAlgorithm algorithm = SshHostKeyAlgorithm.fromName(target.getHostKeyAlgorithm());
        if (algorithm == null) {
            throw new SshConnectionException(SshTestResultCode.HOST_KEY_ALGORITHM_REQUIRED);
        }
        KeyPair identity = loadIdentity(target.getKeyAlias());
        SshTestResultCode precheck = precheckChosenAlgorithm(target, algorithm);
        if (precheck != null) {
            throw new SshConnectionException(precheck);
        }
        return openVerifiedSession(target, algorithm, identity);
    }

    // ==================== 私钥 ====================

    /** 按受限别名加载无口令私钥；带口令或格式不支持按 {@code KEY_UNREADABLE} 处理。 */
    public KeyPair loadIdentity(String keyAlias) {
        Path keyFile = verifiedKeyFile(keyAlias);
        if (isPermissionTooOpen(keyFile)) {
            throw new SshConnectionException(SshTestResultCode.KEY_PERMISSION_TOO_OPEN);
        }
        try (InputStream in = Files.newInputStream(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            for (KeyPair pair : SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(keyAlias), in,
                    FilePasswordProvider.EMPTY)) {
                return pair;
            }
            throw new SshConnectionException(SshTestResultCode.KEY_UNREADABLE);
        } catch (SshConnectionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[RunnerPackage] 私钥加载失败 alias={} 原因={}", keyAlias, e.getClass().getSimpleName());
            throw new SshConnectionException(SshTestResultCode.KEY_UNREADABLE);
        }
    }

    private Path resolveKeyFile(String keyAlias) {
        if (keyAlias == null || !KEY_ALIAS_PATTERN.matcher(keyAlias).matches()
                || ".".equals(keyAlias) || "..".equals(keyAlias)) {
            throw new SshConnectionException(SshTestResultCode.KEY_ALIAS_INVALID);
        }
        Path base = Paths.get(sshSettings.getKeyDir()).toAbsolutePath().normalize();
        Path resolved = base.resolve(keyAlias).normalize();
        if (resolved.equals(base) || !resolved.startsWith(base)) {
            throw new SshConnectionException(SshTestResultCode.KEY_ALIAS_INVALID);
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new SshConnectionException(SshTestResultCode.KEY_ALIAS_INVALID);
        }
        return resolved;
    }

    private Path verifiedKeyFile(String keyAlias) {
        Path candidate = resolveKeyFile(keyAlias);
        Path realBase;
        Path realFile;
        try {
            realBase = Paths.get(sshSettings.getKeyDir()).toAbsolutePath().normalize().toRealPath();
            realFile = candidate.toRealPath();
        } catch (IOException e) {
            throw new SshConnectionException(SshTestResultCode.KEY_NOT_FOUND);
        }
        if (!realFile.startsWith(realBase)) {
            throw new SshConnectionException(SshTestResultCode.KEY_ALIAS_INVALID);
        }
        if (!Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new SshConnectionException(SshTestResultCode.KEY_NOT_FOUND);
        }
        return realFile;
    }

    private boolean isPermissionTooOpen(Path keyFile) {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(keyFile);
        } catch (UnsupportedOperationException e) {
            return false;
        } catch (Exception e) {
            throw new SshConnectionException(SshTestResultCode.KEY_UNREADABLE);
        }
        return permissions.stream().anyMatch(permission -> switch (permission) {
            case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
            default -> false;
        });
    }

    // ==================== 握手 ====================

    /** 阶段一：把可协商的主机密钥算法限制为所选算法，判定服务端是否提供它。 */
    private SshTestResultCode precheckChosenAlgorithm(NexaNodeSshTarget target, SshHostKeyAlgorithm algorithm) {
        AtomicBoolean hostKeyReceived = new AtomicBoolean(false);
        SshClient client = SshClient.setUpDefaultClient();
        client.setSignatureFactories(algorithm.signatureFactories());
        client.setServerKeyVerifier((session, remoteAddress, serverKey) -> {
            hostKeyReceived.set(true);
            return true;
        });
        client.start();
        try {
            ConnectFuture connect = client.connect(target.getUsername(), target.getHost(), target.getPort());
            try {
                connect.verify(sshSettings.getConnectTimeout().toMillis());
            } catch (Exception e) {
                return connect.isDone() ? SshTestResultCode.CONNECT_FAILED : SshTestResultCode.CONNECT_TIMEOUT;
            }
            ClientSession session = connect.getSession();
            try {
                session.auth().verify(sshSettings.getAuthTimeout().toMillis());
            } catch (Exception ignored) {
                // 本阶段只关心主机密钥是否送达，认证结果无关
            }
            if (hostKeyReceived.get()) {
                return null;
            }
            if (session.getServerVersion() != null) {
                log.info("[RunnerPackage] 目标机未提供所选主机密钥算法 目标={}:{} 所选算法={}",
                        target.getHost(), target.getPort(), algorithm);
                return SshTestResultCode.HOST_KEY_ALGORITHM_UNAVAILABLE;
            }
            return SshTestResultCode.CONNECT_TIMEOUT;
        } catch (Exception e) {
            log.info("[RunnerPackage] 所选主机密钥算法预检失败 目标={}:{} 原因={}",
                    target.getHost(), target.getPort(), e.getClass().getSimpleName());
            return SshTestResultCode.CONNECT_FAILED;
        } finally {
            stopQuietly(client);
        }
    }

    /** 阶段二：完整校验（算法家族 + 指纹）并完成公钥认证。 */
    private SshConnection openVerifiedSession(NexaNodeSshTarget target, SshHostKeyAlgorithm chosenAlgorithm,
                                              KeyPair identity) {
        String expectedFingerprint = target.getHostKeySha256();
        AtomicBoolean hostKeyRejected = new AtomicBoolean(false);
        AtomicBoolean keyExchangeReached = new AtomicBoolean(false);

        SshClient client = SshClient.setUpDefaultClient();
        client.setSignatureFactories(hostKeyNegotiationFactories(chosenAlgorithm, identity));
        client.setServerKeyVerifier((session, remoteAddress, serverKey) -> {
            keyExchangeReached.set(true);
            String keyType = KeyUtils.getKeyType(serverKey);
            String observedFingerprint = fingerprintOrUnknown(serverKey);
            boolean algorithmMatched = chosenAlgorithm.matchesKeyType(keyType);
            boolean fingerprintMatched = expectedFingerprint != null
                    && expectedFingerprint.equalsIgnoreCase(observedFingerprint);
            if (!algorithmMatched || !fingerprintMatched) {
                hostKeyRejected.set(true);
                log.warn("[RunnerPackage] 目标机主机密钥校验失败 目标={}:{} 所选算法={} 密钥类型={} 期望指纹={} "
                                + "观察指纹={} 原因={}（观察值仅供运维比对，不得直接回填）",
                        target.getHost(), target.getPort(), chosenAlgorithm, keyType, expectedFingerprint,
                        observedFingerprint, algorithmMatched ? "指纹不一致" : "算法不一致");
            }
            return algorithmMatched && fingerprintMatched;
        });
        client.start();

        try {
            ConnectFuture connect = client.connect(target.getUsername(), target.getHost(), target.getPort());
            try {
                connect.verify(sshSettings.getConnectTimeout().toMillis());
            } catch (Exception e) {
                throw new SshConnectionException(hostKeyRejected.get() ? SshTestResultCode.HOST_KEY_MISMATCH
                        : (connect.isDone() ? SshTestResultCode.CONNECT_FAILED : SshTestResultCode.CONNECT_TIMEOUT));
            }
            if (hostKeyRejected.get()) {
                throw new SshConnectionException(SshTestResultCode.HOST_KEY_MISMATCH);
            }
            ClientSession session = connect.getSession();
            session.addPublicKeyIdentity(identity);

            // SSHD 的 ConnectFuture 在 TCP 层即完成，主机密钥校验发生在认证阶段
            AuthFuture auth = session.auth();
            try {
                auth.verify(sshSettings.getAuthTimeout().toMillis());
            } catch (Exception e) {
                if (hostKeyRejected.get()) {
                    throw new SshConnectionException(SshTestResultCode.HOST_KEY_MISMATCH);
                }
                if (!auth.isDone()) {
                    throw new SshConnectionException(keyExchangeReached.get()
                            ? SshTestResultCode.AUTH_TIMEOUT : SshTestResultCode.CONNECT_TIMEOUT);
                }
                throw new SshConnectionException(keyExchangeReached.get()
                        ? SshTestResultCode.AUTH_FAILED : SshTestResultCode.HOST_KEY_ALGORITHM_UNAVAILABLE);
            }
            if (hostKeyRejected.get()) {
                throw new SshConnectionException(SshTestResultCode.HOST_KEY_MISMATCH);
            }
            return new SshConnection(client, session);
        } catch (SshConnectionException e) {
            stopQuietly(client);
            throw e;
        } catch (Exception e) {
            log.warn("[RunnerPackage] SSH 会话建立异常 目标={}:{} 原因={}",
                    target.getHost(), target.getPort(), e.getClass().getSimpleName());
            stopQuietly(client);
            throw new SshConnectionException(SshTestResultCode.CONNECT_FAILED);
        }
    }

    /** 所选算法排在最前（优先协商），再补充登录密钥算法所需的签名工厂，避免认证失败。 */
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

    /** 只用于日志与比对，不返回公钥内容；无法编码的密钥类型按不匹配处理（fail closed）。 */
    private String fingerprintOrUnknown(PublicKey serverKey) {
        try {
            return KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
        } catch (RuntimeException e) {
            return "<该密钥类型无法计算指纹:" + serverKey.getAlgorithm() + ">";
        }
    }

    private static void stopQuietly(SshClient client) {
        try {
            client.stop();
        } catch (Exception e) {
            log.debug("[RunnerPackage] 关闭 SSH 客户端异常（忽略）: {}", e.getClass().getSimpleName());
        }
    }

    /** 已校验的 SSH 会话（连同一个已启动的客户端）；{@link #close()} 一并释放。 */
    public static final class SshConnection implements Closeable {

        private final SshClient client;
        private final ClientSession session;

        SshConnection(SshClient client, ClientSession session) {
            this.client = client;
            this.session = session;
        }

        public ClientSession session() {
            return session;
        }

        @Override
        public void close() {
            try {
                session.close(true);
            } catch (Exception ignored) {
                // 关闭失败无需上报
            }
            stopQuietly(client);
        }
    }

    /** 会话建立失败的载体：只携带阶段 1 的结果码，不携带远端输出或异常栈。 */
    public static final class SshConnectionException extends RuntimeException {

        private final SshTestResultCode code;

        public SshConnectionException(SshTestResultCode code) {
            super(code.name());
            this.code = code;
        }

        public SshTestResultCode code() {
            return code;
        }
    }
}
