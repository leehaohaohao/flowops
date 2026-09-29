package com.nexa.flowops.service.nodepackage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布包 v1 校验器（阶段 2 P3）：按 {@code docs/runner-package-format.md} §1–§5 与 §7 校验上传内容。
 *
 * <p>行为边界：
 * <ul>
 *   <li><b>单遍流式</b>：边读边算 {@code .tar.gz} 摘要、边解析 gzip/tar，同时把原始字节写入调用方给的临时文件；
 *       不整份缓冲内存，成员内容只用于计算摘要（不解压落盘、不执行任何包内内容）。</li>
 *   <li><b>成员白名单</b>：只接受格式 v1 固定的 6 个成员、固定权限、普通文件、uid/gid=0 且 uname/gname 为空，
 *       拒绝绝对路径 / {@code ..} / 反斜杠 / 重复成员 / 白名单外成员；同时限制成员数、单成员与解压总计。</li>
 *   <li><b>严格 manifest</b>：未知字段直接失败（格式 v1 字段集固定），阻断"把节点凭据附加进 manifest"的路径。</li>
 *   <li><b>prod/dev 兼容</b>：只按 {@code package.version} 做 SemVer 校验（dev 包带 {@code -dev} 后缀），
 *       不要求、也不接受任何"环境"字段；构建环境由阶段 3 从版本后缀推导。</li>
 *   <li>失败只抛 {@link RunnerPackageException}（固定原因 + 固定中文文案），不含包内容或异常栈细节。</li>
 * </ul>
 *
 * <p>与执行器仓库 {@code deploy/packager verify} 的判定保持一致（六成员、权限、uid/gid、mtime、checksums、
 * manifest 字段），另外增加了未知字段与成员路径的白名单拒绝。
 */
@Component
@RequiredArgsConstructor
public class RunnerPackageValidator {

    private static final Logger log = LoggerFactory.getLogger(RunnerPackageValidator.class);

    static final String PACKAGE_NAME = "flowops-executor";
    static final String PACKAGE_OS = "linux";
    static final String PACKAGE_ARCH = "amd64";

    static final String MANIFEST_PATH = "manifest.json";
    static final String CHECKSUMS_PATH = "checksums.txt";
    static final String BINARY_PATH = "bin/flowops-executor";
    static final String IMAGE_PATH = "images/flowops-executor.tar";
    static final String COMPOSE_PATH = "templates/docker-compose.yaml";
    static final String SYSTEMD_PATH = "templates/flowops-executor.service";

    /** 归档固定成员 → 权限（契约 §2） */
    static final Map<String, String> MEMBER_MODES = memberModes();
    /** 载荷成员（manifest.members 必须恰好列出这 4 个，按 path 升序） */
    static final List<String> PAYLOAD_MEMBERS =
            List.of(BINARY_PATH, IMAGE_PATH, COMPOSE_PATH, SYSTEMD_PATH);
    /** checksums.txt 覆盖的成员：载荷 4 个 + manifest.json（不含自身） */
    static final List<String> CHECKSUMMED_MEMBERS = checksummedMembers();
    /** 需要在内存中解析的结构性文本成员（其余成员只算摘要） */
    private static final Set<String> TEXT_MEMBERS = Set.of(MANIFEST_PATH, CHECKSUMS_PATH);

    private static final Pattern FILE_NAME = Pattern.compile(
            "^" + PACKAGE_NAME + "-([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.]+)?)-"
                    + PACKAGE_OS + "-" + PACKAGE_ARCH + "\\.tar\\.gz$");
    private static final Pattern VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.]+)?$");
    private static final Pattern GIT_COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern IMAGE_ID = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern MEMBER_PATH = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final Pattern CHECKSUM_LINE = Pattern.compile("^([0-9a-f]{64})  ([A-Za-z0-9._/-]+)$");

    /** 结构性文本成员的大小上限（防止畸形包把 manifest/checksums 撑爆内存） */
    private static final long TEXT_MEMBER_LIMIT = 256 * 1024;

    private static final HexFormat HEX = HexFormat.of();

    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final RunnerPackageSettings settings;

    /**
     * 校验一个上传的发布包。
     *
     * @param raw              上传的 {@code .tar.gz} 原始字节流（由调用方关闭）
     * @param uploadedFileName 上传文件名（须匹配契约命名规则）
     * @param tempFile         接收原始字节的临时文件（失败时由调用方删除）
     * @return 校验通过后的包事实
     * @throws RunnerPackageException 校验失败（固定原因 + 固定中文文案）
     */
    public RunnerPackageInspection validate(InputStream raw, String uploadedFileName, Path tempFile) {
        // 上传文件名可能带客户端路径前缀（不同浏览器行为不一），先归一化为纯文件名
        String fileName = normalizeFileName(uploadedFileName);
        String versionFromName = requireValidFileName(fileName);
        long maxSize = settings.getMaxSize().toBytes();

        Map<String, byte[]> textMembers = new HashMap<>();
        Map<String, String> memberDigests = new LinkedHashMap<>();
        Map<String, Long> memberSizes = new HashMap<>();
        Map<String, String> memberModes = new HashMap<>();
        Map<String, Long> memberMtimes = new HashMap<>();
        long totalUncompressed = 0;
        boolean identityClean = true;

        // 第一段：把上传字节流式写入临时文件并同步计算摘要（边读边限长，不整份缓冲内存）。
        // 摘要覆盖的正是临时文件里的字节，二者必然一致；后续校验只读这个本地文件，
        // 不再让解压流直接消费网络流（避免解压器的 mark/reset 语义影响落盘内容）。
        MessageDigest packageDigest = PackageDigests.newDigest();
        long consumed = 0;
        try (InputStream in = new BufferedInputStream(raw, 64 * 1024);
             OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                consumed += read;
                if (consumed > maxSize) {
                    throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_TOO_LARGE);
                }
                packageDigest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (RunnerPackageException e) {
            throw e;
        } catch (IOException e) {
            log.warn("[RunnerPackage] 读取上传流失败: {}", e.getClass().getSimpleName());
            throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
        }

        if (consumed == 0) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_EMPTY);
        }

        // 第二段：从临时文件只读遍历 gzip/tar，逐成员做白名单、权限、大小与摘要校验。
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(tempFile), 64 * 1024);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(fileIn, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {

            byte[] buffer = new byte[64 * 1024];
            TarArchiveEntry entry;
            int entries = 0;
            while ((entry = tar.getNextEntry()) != null) {
                entries++;
                if (entries > settings.getMaxEntries()) {
                    throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID,
                            "归档成员数超过上限 " + settings.getMaxEntries());
                }
                String path = entry.getName();
                validateMemberPath(path);
                String expectedMode = MEMBER_MODES.get(path);
                if (expectedMode == null) {
                    throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID,
                            "白名单之外的成员 " + path);
                }
                if (memberDigests.containsKey(path)) {
                    throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID, "成员重复 " + path);
                }
                if (entry.getLinkFlag() != TarConstants.LF_NORMAL
                        && entry.getLinkFlag() != TarConstants.LF_OLDNORM) {
                    // 只接受普通文件：目录/符号链接/硬链接/设备/FIFO 一律拒绝
                    // （注意 TarArchiveEntry#isFile 对链接也返回 true，必须看 linkFlag）
                    throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID,
                            "成员必须是普通文件 " + path);
                }
                String mode = String.format("%04o", entry.getMode() & 07777);
                if (!mode.equals(expectedMode)) {
                    throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID,
                            "成员权限不符 " + path + " = " + mode);
                }
                if (entry.getLongUserId() != 0 || entry.getLongGroupId() != 0
                        || !isBlank(entry.getUserName()) || !isBlank(entry.getGroupName())) {
                    identityClean = false;
                }
                long size = entry.getSize();
                if (size < 0 || size > settings.getMaxMemberSize().toBytes()) {
                    throw new RunnerPackageException(RunnerPackageFailure.MEMBER_TOO_LARGE, path);
                }
                totalUncompressed += size;
                if (totalUncompressed > settings.getMaxTotalUncompressedSize().toBytes()) {
                    throw new RunnerPackageException(RunnerPackageFailure.MEMBER_TOO_LARGE, "解压总计超过上限");
                }
                if (TEXT_MEMBERS.contains(path) && size > TEXT_MEMBER_LIMIT) {
                    throw new RunnerPackageException(RunnerPackageFailure.MEMBER_TOO_LARGE, path);
                }

                MessageDigest memberDigest = PackageDigests.newDigest();
                ByteArrayOutputStream text = TEXT_MEMBERS.contains(path) ? new ByteArrayOutputStream() : null;
                long read = 0;
                int len;
                while (read < size
                        && (len = tar.read(buffer, 0, (int) Math.min(buffer.length, size - read))) != -1) {
                    read += len;
                    memberDigest.update(buffer, 0, len);
                    if (text != null) {
                        text.write(buffer, 0, len);
                    }
                }
                if (read != size) {
                    throw new RunnerPackageException(RunnerPackageFailure.ARCHIVE_INVALID,
                            "成员长度不符 " + path);
                }
                memberDigests.put(path, HEX.formatHex(memberDigest.digest()));
                memberSizes.put(path, size);
                memberModes.put(path, mode);
                memberMtimes.put(path, entry.getModTime().toInstant().getEpochSecond());
                if (text != null) {
                    textMembers.put(path, text.toByteArray());
                }
            }

            // 归档在 tar 结尾之后不应再有数据（拼接的 gzip 成员或多余字节）
            long trailing = 0;
            int extra;
            while ((extra = tar.read(buffer)) != -1) {
                trailing += extra;
            }
            if (trailing > 0) {
                throw new RunnerPackageException(RunnerPackageFailure.ARCHIVE_INVALID, "归档在 tar 结尾后仍有数据");
            }
        } catch (RunnerPackageException e) {
            throw e;
        } catch (IOException e) {
            log.info("[RunnerPackage] 归档解析失败，已按非法包拒绝: {}", e.getClass().getSimpleName());
            throw new RunnerPackageException(RunnerPackageFailure.ARCHIVE_INVALID);
        }

        if (!memberDigests.keySet().equals(MEMBER_MODES.keySet())) {
            Set<String> missing = new HashSet<>(MEMBER_MODES.keySet());
            missing.removeAll(memberDigests.keySet());
            throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID,
                    "缺失成员 " + String.join(", ", new TreeSet<>(missing)));
        }
        if (!identityClean) {
            throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID,
                    "成员 uid/gid 必须为 0 且 uname/gname 必须为空");
        }

        RunnerPackageManifest manifest = parseManifest(textMembers.get(MANIFEST_PATH));
        checkManifest(manifest, fileName, versionFromName);
        checkMembersAgainstManifest(manifest, memberSizes, memberModes);
        checkSourceDateEpoch(manifest, memberMtimes);
        checkChecksums(textMembers.get(CHECKSUMS_PATH), memberDigests);

        String sha256 = HEX.formatHex(packageDigest.digest());
        List<String> members = new ArrayList<>(memberDigests.keySet());
        members.sort(String::compareTo);
        return new RunnerPackageInspection(sha256, consumed, fileName, manifest.getPkg().getVersion(),
                manifest.getPkg().getOs(), manifest.getPkg().getArch(), manifest.getPackageFormatVersion(),
                manifest.getPkg().getGitCommit(), manifest.getPkg().getSourceDateEpoch(),
                manifest.getImage().getReference(), manifest.getImage().getImageId(),
                manifest.getImage().getDockerCliVersion(), manifest.getImage().getComposeVersion(), members);
    }

    /** 早期廉价检查：文件名必须符合契约命名规则，返回其中的版本号。 */
    public String requireValidFileName(String uploadedFileName) {
        String name = normalizeFileName(uploadedFileName);
        Matcher matcher = FILE_NAME.matcher(name);
        if (!matcher.matches()) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NAME_MISMATCH);
        }
        return matcher.group(1);
    }

    /** 浏览器可能带上客户端路径（不同平台分隔符不一），只保留纯文件名。 */
    static String normalizeFileName(String uploadedFileName) {
        if (uploadedFileName == null) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NAME_MISMATCH);
        }
        String name = uploadedFileName.replace('\\', '/').strip();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank()) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NAME_MISMATCH);
        }
        return name;
    }

    /** 是否为合法的包摘要（64 位小写十六进制）；用于路径参数校验。 */
    public static boolean isSha256Hex(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    // ==================== 内部校验 ====================

    private void checkManifest(RunnerPackageManifest manifest, String uploadedFileName, String versionFromName) {
        Integer formatVersion = manifest.getPackageFormatVersion();
        if (formatVersion == null || formatVersion != 1) {
            throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_FORMAT_UNSUPPORTED);
        }
        RunnerPackageManifest.PackageSection pkg = require(manifest.getPkg(), "package");
        requireTrue(PACKAGE_NAME.equals(pkg.getName()), "package.name");
        String version = require(pkg.getVersion(), "package.version");
        requireTrue(VERSION.matcher(version).matches(), "package.version");
        if (!PACKAGE_OS.equals(pkg.getOs()) || !PACKAGE_ARCH.equals(pkg.getArch())) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_PLATFORM_UNSUPPORTED);
        }
        requireTrue(GIT_COMMIT.matcher(require(pkg.getGitCommit(), "package.gitCommit")).matches(),
                "package.gitCommit");
        Long epoch = require(pkg.getSourceDateEpoch(), "package.sourceDateEpoch");
        requireTrue(epoch > 0, "package.sourceDateEpoch");
        String fileName = require(pkg.getFileName(), "package.fileName");
        if (!fileName.equals(uploadedFileName)) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NAME_MISMATCH,
                    "manifest.package.fileName = " + fileName);
        }
        if (!fileName.equals(PACKAGE_NAME + "-" + version + "-" + PACKAGE_OS + "-" + PACKAGE_ARCH + ".tar.gz")) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NAME_VERSION_MISMATCH);
        }
        if (!version.equals(versionFromName)) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NAME_VERSION_MISMATCH);
        }

        RunnerPackageManifest.BinarySection binary = require(manifest.getBinary(), "binary");
        requireTrue(BINARY_PATH.equals(binary.getPath()), "binary.path");
        requireTrue(isNotBlank(binary.getGoVersion()), "binary.goVersion");

        RunnerPackageManifest.ImageSection image = require(manifest.getImage(), "image");
        requireTrue(IMAGE_PATH.equals(image.getPath()), "image.path");
        requireTrue(IMAGE_ID.matcher(require(image.getImageId(), "image.imageId")).matches(), "image.imageId");
        requireTrue(isNotBlank(image.getReference()), "image.reference");
        requireTrue(isNotBlank(image.getDockerCliVersion()), "image.dockerCliVersion");
        requireTrue(isNotBlank(image.getComposeVersion()), "image.composeVersion");

        RunnerPackageManifest.TemplateSection templates = require(manifest.getTemplates(), "templates");
        requireTrue(SYSTEMD_PATH.equals(templates.getSystemd()), "templates.systemd");
        requireTrue(COMPOSE_PATH.equals(templates.getCompose()), "templates.compose");
    }

    private void checkMembersAgainstManifest(RunnerPackageManifest manifest,
                                             Map<String, Long> memberSizes,
                                             Map<String, String> memberModes) {
        List<RunnerPackageManifest.Member> members = require(manifest.getMembers(), "members");
        if (members.size() != PAYLOAD_MEMBERS.size()) {
            throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID,
                    "members 数量不符");
        }
        List<String> seen = new ArrayList<>();
        for (RunnerPackageManifest.Member member : members) {
            String path = require(member.getPath(), "members[].path");
            requireTrue(PAYLOAD_MEMBERS.contains(path), "members[].path");
            if (!seen.isEmpty() && seen.get(seen.size() - 1).compareTo(path) >= 0) {
                throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID,
                        "members 未按 path 升序");
            }
            seen.add(path);
            Long size = require(member.getSize(), "members[].size");
            if (!size.equals(memberSizes.get(path))) {
                throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID,
                        "members[].size 与归档不符 " + path);
            }
            String mode = require(member.getMode(), "members[].mode");
            if (!mode.equals(memberModes.get(path))) {
                throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID,
                        "members[].mode 与归档不符 " + path);
            }
        }
        if (!new HashSet<>(seen).equals(new HashSet<>(PAYLOAD_MEMBERS))) {
            throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID, "members 集合不符");
        }
    }

    private void checkSourceDateEpoch(RunnerPackageManifest manifest, Map<String, Long> memberMtimes) {
        long epoch = manifest.getPkg().getSourceDateEpoch();
        for (Map.Entry<String, Long> entry : memberMtimes.entrySet()) {
            if (entry.getValue() != epoch) {
                throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID,
                        "成员 mtime 与 sourceDateEpoch 不符 " + entry.getKey());
            }
        }
    }

    private void checkChecksums(byte[] raw, Map<String, String> memberDigests) {
        if (raw == null) {
            throw new RunnerPackageException(RunnerPackageFailure.CHECKSUMS_INVALID);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.contains("\r") || !text.endsWith("\n")) {
            throw new RunnerPackageException(RunnerPackageFailure.CHECKSUMS_INVALID);
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length != CHECKSUMMED_MEMBERS.size()) {
            throw new RunnerPackageException(RunnerPackageFailure.CHECKSUMS_INVALID, "行数不符");
        }
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (String line : lines) {
            Matcher matcher = CHECKSUM_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new RunnerPackageException(RunnerPackageFailure.CHECKSUMS_INVALID);
            }
            String hex = matcher.group(1);
            String path = matcher.group(2);
            if (previous != null && path.compareTo(previous) <= 0) {
                throw new RunnerPackageException(RunnerPackageFailure.CHECKSUMS_INVALID, "未按 path 升序");
            }
            previous = path;
            if (!CHECKSUMMED_MEMBERS.contains(path) || !seen.add(path)) {
                throw new RunnerPackageException(RunnerPackageFailure.CHECKSUMS_INVALID,
                        "引用了非预期成员 " + path);
            }
            if (!hex.equals(memberDigests.get(path))) {
                throw new RunnerPackageException(RunnerPackageFailure.CHECKSUM_MISMATCH, path);
            }
        }
        if (!seen.equals(new HashSet<>(CHECKSUMMED_MEMBERS))) {
            throw new RunnerPackageException(RunnerPackageFailure.CHECKSUMS_INVALID, "缺少成员");
        }
    }

    private RunnerPackageManifest parseManifest(byte[] raw) {
        if (raw == null) {
            throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID, "缺少 manifest.json");
        }
        try {
            return MANIFEST_MAPPER.readValue(raw, RunnerPackageManifest.class);
        } catch (Exception e) {
            // 不返回解析器细节，避免把包内容带进响应
            throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID, "JSON 无法解析");
        }
    }

    private void validateMemberPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 200) {
            throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID, "成员路径长度非法");
        }
        if (!MEMBER_PATH.matcher(path).matches()) {
            throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID, "成员路径含非法字符");
        }
        if (path.startsWith("/") || path.indexOf('\\') >= 0) {
            throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID, "成员路径不得为绝对路径");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new RunnerPackageException(RunnerPackageFailure.MEMBER_INVALID, "成员路径含非法路径段");
            }
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID, "缺少字段 " + field);
        }
        return value;
    }

    private static void requireTrue(boolean condition, String field) {
        if (!condition) {
            throw new RunnerPackageException(RunnerPackageFailure.MANIFEST_INVALID, "字段非法 " + field);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    private static Map<String, String> memberModes() {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put(MANIFEST_PATH, "0644");
        modes.put(CHECKSUMS_PATH, "0644");
        modes.put(BINARY_PATH, "0755");
        modes.put(IMAGE_PATH, "0644");
        modes.put(COMPOSE_PATH, "0644");
        modes.put(SYSTEMD_PATH, "0644");
        return Map.copyOf(modes);
    }

    private static List<String> checksummedMembers() {
        List<String> members = new ArrayList<>(PAYLOAD_MEMBERS);
        members.add(MANIFEST_PATH);
        members.sort(String::compareTo);
        return List.copyOf(members);
    }
}
