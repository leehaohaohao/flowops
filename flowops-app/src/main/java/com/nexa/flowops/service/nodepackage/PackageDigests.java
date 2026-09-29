package com.nexa.flowops.service.nodepackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 发布包摘要工具：统一"小写十六进制 64 位"表示，供校验、存储与分发复用。 */
public final class PackageDigests {

    private static final HexFormat HEX = HexFormat.of();

    private PackageDigests() {
    }

    public static String sha256Hex(byte[] data) {
        return HEX.formatHex(newDigest().digest(data));
    }

    public static String sha256Hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256Hex(in);
        }
    }

    public static String sha256Hex(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HEX.formatHex(digest.digest());
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
