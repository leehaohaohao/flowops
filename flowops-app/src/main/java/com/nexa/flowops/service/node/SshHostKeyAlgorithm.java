package com.nexa.flowops.service.node;

import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 主机密钥算法（H1.2 契约）：与 {@code hostKeySha256} 成对使用。
 *
 * <p>目标机可能同时启用多把主机密钥（Ed25519 / ECDSA / RSA），"主机密钥指纹"只有在指明算法后才唯一。
 * 管理员必须选择算法，并填写**该算法对应**的目标机主机公钥指纹
 * （如 ED25519 对应 {@code /etc/ssh/ssh_host_ed25519_key.pub}）。
 *
 * <p>历史上只保存了指纹、没有算法的记录不允许继续测试（见 {@link SshTestResultCode#HOST_KEY_ALGORITHM_REQUIRED}），
 * 以免静默假定为某一种算法。
 */
public enum SshHostKeyAlgorithm {

    /** ssh-ed25519 */
    ED25519,
    /** ecdsa-sha2-nistp256 / nistp384 / nistp521 */
    ECDSA,
    /** rsa-sha2-256 / rsa-sha2-512 / ssh-rsa */
    RSA;

    /** 解析并归一化算法名；无法识别时返回 null。 */
    public static SshHostKeyAlgorithm fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(algorithm -> algorithm.name().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    /** web 展示用的算法名列表，供前端（H1.4）与文档共用。 */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /**
     * 限制客户端可协商的主机密钥签名工厂（H1.3）：
     * 只包含本算法的工厂，因此服务端若不提供该算法，握手会因无共同算法而失败。
     */
    public List<NamedFactory<Signature>> signatureFactories() {
        return switch (this) {
            case ED25519 -> List.of(BuiltinSignatures.ed25519);
            case ECDSA -> List.of(BuiltinSignatures.nistp256, BuiltinSignatures.nistp384, BuiltinSignatures.nistp521);
            // 现代优先：OpenSSH 默认接受 rsa-sha2-*，老服务端只接受 ssh-rsa
            case RSA -> List.of(BuiltinSignatures.rsaSHA256, BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsa);
        };
    }

    /**
     * 判断握手协商到的密钥类型是否属于本算法（H1.3 的"同时核对算法"）。
     *
     * <p>只做算法家族判定，指纹比对由调用方完成。
     */
    public boolean matchesKeyType(String keyType) {
        if (keyType == null || keyType.isBlank()) {
            return false;
        }
        String canonical;
        try {
            canonical = KeyUtils.getCanonicalKeyType(keyType);
        } catch (RuntimeException e) {
            return false;
        }
        return switch (this) {
            case ED25519 -> "ssh-ed25519".equals(canonical);
            case RSA -> "ssh-rsa".equals(canonical);
            case ECDSA -> canonical != null && canonical.startsWith("ecdsa-sha2-nistp");
        };
    }
}
