package com.nexa.flowops.service.nodepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 测试用发布包构造器：按 {@code docs/runner-package-format.md} v1 生成 {@code .tar.gz}
 * （与执行器仓库 {@code deploy/packager} 同一格式：成员按 path 升序、uid/gid=0、uname/gname 为空、
 * mtime = sourceDateEpoch、gzip 不留文件名/时间戳），并提供可控的"篡改"入口。
 *
 * <p>开发/生产（{@code -dev} 后缀）包使用同一个构造器：格式 v1 不区分构建环境，
 * 环境只体现在版本后缀与包内容里。
 */
final class RunnerPackageTestFixture {

    static final long EPOCH = 1758931200L;
    static final String GIT_COMMIT = "4786ea0020f471c6ddf53ebef098490ad7f641b6";
    static final String IMAGE_ID = "sha256:" + "9f2c1e0b7a4d5c8e6f3a2b1d0c9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private final List<Entry> entries = new ArrayList<>();
    private UnaryOperator<byte[]> manifestMutator = UnaryOperator.identity();
    private UnaryOperator<byte[]> checksumsMutator = UnaryOperator.identity();

    private RunnerPackageTestFixture() {
    }

    /** 默认的 prod（无 -dev 后缀）包。 */
    static RunnerPackageTestFixture prod() {
        return ofVersion("0.7.0");
    }

    /** 默认的 dev 包（版本带 -dev 后缀，见格式契约 §11）。 */
    static RunnerPackageTestFixture dev() {
        return ofVersion("0.7.0-dev");
    }

    static RunnerPackageTestFixture ofVersion(String version) {
        RunnerPackageTestFixture fixture = new RunnerPackageTestFixture();
        fixture.entries.add(entry("bin/flowops-executor", "#!/bin/sh\necho flowops-executor\n".getBytes(StandardCharsets.UTF_8), "0755"));
        fixture.entries.add(entry("images/flowops-executor.tar", ("fake-image-tar-" + version).getBytes(StandardCharsets.UTF_8), "0644"));
        fixture.entries.add(entry("templates/docker-compose.yaml", "services:\n  executor:\n    image: \"{{IMAGE_REFERENCE}}\"\n".getBytes(StandardCharsets.UTF_8), "0644"));
        fixture.entries.add(entry("templates/flowops-executor.service", "[Service]\nExecStart={{RELEASE_DIR}}/bin/flowops-executor\n".getBytes(StandardCharsets.UTF_8), "0644"));
        fixture.entries.add(entry("manifest.json", new byte[0], "0644"));
        fixture.entries.add(entry("checksums.txt", new byte[0], "0644"));
        fixture.version = version;
        return fixture;
    }

    private String version;
    private String fileName;
    private String os = "linux";
    private String arch = "amd64";
    private int formatVersion = 1;
    private String gitCommit = GIT_COMMIT;
    private String goVersion = "go1.26.3";
    private String imageReference;
    private String imageId = IMAGE_ID;
    private String dockerCliVersion = "27.3.1";
    private String composeVersion = "v2.29.7";
    private long sourceDateEpoch = EPOCH;

    // ==================== 可控篡改入口 ====================

    List<Entry> entries() {
        return entries;
    }

    Entry entry(String path) {
        return entries.stream().filter(e -> e.path.equals(path)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("缺少成员: " + path));
    }

    RunnerPackageTestFixture fileName(String value) {
        this.fileName = value;
        return this;
    }

    RunnerPackageTestFixture os(String value) {
        this.os = value;
        return this;
    }

    RunnerPackageTestFixture arch(String value) {
        this.arch = value;
        return this;
    }

    RunnerPackageTestFixture formatVersion(int value) {
        this.formatVersion = value;
        return this;
    }

    RunnerPackageTestFixture gitCommit(String value) {
        this.gitCommit = value;
        return this;
    }

    RunnerPackageTestFixture imageId(String value) {
        this.imageId = value;
        return this;
    }

    RunnerPackageTestFixture imageReference(String value) {
        this.imageReference = value;
        return this;
    }

    RunnerPackageTestFixture sourceDateEpoch(long value) {
        this.sourceDateEpoch = value;
        return this;
    }

    RunnerPackageTestFixture manifestMutator(UnaryOperator<byte[]> mutator) {
        this.manifestMutator = mutator;
        return this;
    }

    RunnerPackageTestFixture checksumsMutator(UnaryOperator<byte[]> mutator) {
        this.checksumsMutator = mutator;
        return this;
    }

    /** 追加一个白名单之外的成员（例如试图夹带节点凭据文件）。 */
    RunnerPackageTestFixture extraMember(String path, String content) {
        entries.add(entry(path, content.getBytes(StandardCharsets.UTF_8), "0644"));
        return this;
    }

    /** 删除一个成员（缺失必需成员）。 */
    RunnerPackageTestFixture removeMember(String path) {
        entries.removeIf(e -> e.path.equals(path));
        return this;
    }

    /** 在 manifest 根对象上追加字段（用于未知字段 / 夹带凭据的用例）。 */
    RunnerPackageTestFixture extraManifestField(String key, Object value) {
        return manifestMutator(bytes -> {
            Map<String, Object> root = readJson(bytes);
            root.put(key, value);
            return writeJson(root);
        });
    }

    // ==================== 产出 ====================

    byte[] build() {
        applyManifest();
        applyChecksums();
        return writeArchive();
    }

    String sha256() {
        return sha256Hex(build());
    }

    String expectedFileName() {
        return fileName != null ? fileName : defaultFileName();
    }

    private String defaultFileName() {
        return "flowops-executor-" + version + "-" + os + "-" + arch + ".tar.gz";
    }

    private void applyManifest() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("packageFormatVersion", formatVersion);

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("name", "flowops-executor");
        pkg.put("version", version);
        pkg.put("os", os);
        pkg.put("arch", arch);
        pkg.put("fileName", expectedFileName());
        pkg.put("gitCommit", gitCommit);
        pkg.put("sourceDateEpoch", sourceDateEpoch);
        root.put("package", pkg);

        Map<String, Object> binary = new LinkedHashMap<>();
        binary.put("path", "bin/flowops-executor");
        binary.put("goVersion", goVersion);
        root.put("binary", binary);

        Map<String, Object> image = new LinkedHashMap<>();
        image.put("path", "images/flowops-executor.tar");
        image.put("reference", imageReference != null ? imageReference : "flowops-executor:" + version);
        image.put("imageId", imageId);
        image.put("dockerCliVersion", dockerCliVersion);
        image.put("composeVersion", composeVersion);
        root.put("image", image);

        Map<String, Object> templates = new LinkedHashMap<>();
        templates.put("systemd", "templates/flowops-executor.service");
        templates.put("compose", "templates/docker-compose.yaml");
        root.put("templates", templates);

        List<Map<String, Object>> members = new ArrayList<>();
        for (String path : List.of("bin/flowops-executor", "images/flowops-executor.tar",
                "templates/docker-compose.yaml", "templates/flowops-executor.service")) {
            Entry entry = entries.stream().filter(e -> e.path.equals(path)).findFirst().orElse(null);
            if (entry == null) {
                continue;
            }
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("path", path);
            member.put("size", entry.size());
            member.put("mode", entry.mode);
            members.add(member);
        }
        root.put("members", members);

        byte[] json = writeJson(root);
        entry("manifest.json").data = manifestMutator.apply(json);
    }

    private void applyChecksums() {
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (!entry.path.equals("checksums.txt")) {
                inputs.put(entry.path, entry.data);
            }
        }
        List<String> paths = new ArrayList<>(inputs.keySet());
        paths.sort(String::compareTo);
        StringBuilder builder = new StringBuilder();
        for (String path : paths) {
            builder.append(sha256Hex(inputs.get(path))).append("  ").append(path).append('\n');
        }
        entry("checksums.txt").data = checksumsMutator.apply(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] writeArchive() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                List<Entry> ordered = new ArrayList<>(entries);
                ordered.sort((a, b) -> a.path.compareTo(b.path));
                for (Entry entry : ordered) {
                    TarArchiveEntry header = new TarArchiveEntry(entry.path, entry.typeflag);
                    header.setSize(entry.typeflag == TarConstants.LF_NORMAL ? entry.data.length : 0);
                    header.setMode(Integer.parseInt(entry.mode, 8));
                    header.setUserId(entry.uid);
                    header.setGroupId(entry.gid);
                    header.setUserName(entry.uname);
                    header.setGroupName(entry.gname);
                    header.setModTime(entry.mtimeSec * 1000L);
                    if (entry.linkName != null) {
                        header.setLinkName(entry.linkName);
                    }
                    tar.putArchiveEntry(header);
                    if (entry.typeflag == TarConstants.LF_NORMAL) {
                        tar.write(entry.data);
                    }
                    tar.closeArchiveEntry();
                }
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("构造测试发布包失败", e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String imageIdFor(int seed) {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (seed + i);
        }
        return "sha256:" + HEX.formatHex(raw);
    }

    static String base64Fingerprint(byte[] raw) {
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(raw);
    }

    private static Map<String, Object> readJson(byte[] bytes) {
        try {
            return MAPPER.readValue(bytes, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] writeJson(Map<String, Object> value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Entry entry(String path, byte[] data, String mode) {
        Entry entry = new Entry();
        entry.path = path;
        entry.data = data;
        entry.mode = mode;
        return entry;
    }

    /** 归档成员：字段可变，便于用例直接制造权限/mtime/uid/类型/路径等异常。 */
    static final class Entry {
        String path;
        byte[] data;
        String mode;
        int uid = 0;
        int gid = 0;
        String uname = "";
        String gname = "";
        long mtimeSec = EPOCH;
        byte typeflag = TarConstants.LF_NORMAL;
        String linkName;

        long size() {
            return data == null ? 0 : data.length;
        }

        Entry mode(String value) {
            this.mode = value;
            return this;
        }

        Entry mtime(long value) {
            this.mtimeSec = value;
            return this;
        }

        Entry path(String value) {
            this.path = value;
            return this;
        }

        Entry type(byte value) {
            this.typeflag = value;
            return this;
        }

        Entry link(String value) {
            this.linkName = value;
            return this;
        }

        Entry uid(int value) {
            this.uid = value;
            return this;
        }

        Entry uname(String value) {
            this.uname = value;
            return this;
        }
    }
}
