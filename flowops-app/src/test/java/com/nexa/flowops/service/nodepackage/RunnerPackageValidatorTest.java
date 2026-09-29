package com.nexa.flowops.service.nodepackage;

import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布包 v1 校验器测试：接受真实格式的 prod / dev 包，并拒绝各类篡改与越界内容。
 *
 * <p>契约：{@code docs/runner-package-format.md}；用例对应阶段 2 P3 验证项
 * （正确包、篡改包、重传、六成员、权限/uid/mtime、路径越界）与 P3-C 的
 * "prod/dev 包各自独立摘要、不接收节点凭据"。
 */
class RunnerPackageValidatorTest {

    @TempDir
    Path tempDir;

    private final RunnerPackageSettings settings = new RunnerPackageSettings();

    private RunnerPackageValidator validator() {
        return new RunnerPackageValidator(settings);
    }

    // ==================== 接受：prod / dev ====================

    @Test
    void acceptsProdPackageAndReportsDigestAndMembers() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        Path temp = tempFile();

        RunnerPackageInspection inspection = validate(bytes, fixture.expectedFileName(), temp);

        assertEquals(fixture.sha256(), inspection.sha256(), "包身份应等于 .tar.gz 字节的 sha256");
        assertEquals(bytes.length, inspection.sizeBytes());
        assertEquals(bytes.length, Files.size(temp), "校验时写入的临时文件应与上传字节完全一致");
        assertEquals("0.7.0", inspection.version());
        assertEquals("linux", inspection.os());
        assertEquals("amd64", inspection.arch());
        assertEquals(1, inspection.formatVersion());
        assertEquals(RunnerPackageTestFixture.GIT_COMMIT, inspection.gitCommit());
        assertEquals("flowops-executor:0.7.0", inspection.imageReference());
        assertEquals(RunnerPackageTestFixture.IMAGE_ID, inspection.imageId());
        assertEquals(6, inspection.members().size());
        assertEquals(List.of("bin/flowops-executor", "checksums.txt", "images/flowops-executor.tar",
                        "manifest.json", "templates/docker-compose.yaml", "templates/flowops-executor.service"),
                inspection.members(), "成员按 path 升序且恰好 6 个");
    }

    @Test
    void acceptsDevPackageWithPrereleaseSuffix() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.dev();
        byte[] bytes = fixture.build();

        RunnerPackageInspection inspection = validate(bytes, fixture.expectedFileName(), tempFile());

        assertEquals("0.7.0-dev", inspection.version(), "dev 包版本带 -dev 后缀（格式契约 §11）");
        assertEquals("flowops-executor:0.7.0-dev", inspection.imageReference());
        assertEquals(6, inspection.members().size());
    }

    @Test
    void prodAndDevPackagesHaveIndependentDigests() {
        String prodDigest = RunnerPackageTestFixture.prod().sha256();
        String devDigest = RunnerPackageTestFixture.dev().sha256();

        assertNotEquals(prodDigest, devDigest, "prod/dev 包必须各自独立摘要，不能互相覆盖");
    }

    @Test
    void acceptsFileNameCarryingClientPathPrefix() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();

        RunnerPackageInspection inspection = validate(fixture.build(),
                "C:\\fakepath\\" + fixture.expectedFileName(), tempFile());

        assertEquals(fixture.expectedFileName(), inspection.fileName());
    }

    // ==================== 拒绝：命名、平台、格式版本 ====================

    @Test
    void rejectsPackageNameNotMatchingContract() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();

        assertFailure(RunnerPackageFailure.PACKAGE_NAME_MISMATCH, fixture.build(),
                "flowops-executor-0.7.0-linux-arm64.tar.gz");
        assertFailure(RunnerPackageFailure.PACKAGE_NAME_MISMATCH, fixture.build(), "flowops-executor.tar.gz");
        assertFailure(RunnerPackageFailure.PACKAGE_NAME_MISMATCH, fixture.build(), "other-0.7.0-linux-amd64.tar.gz");
    }

    @Test
    void rejectsManifestFileNameInconsistentWithVersion() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod()
                .fileName("flowops-executor-0.7.1-linux-amd64.tar.gz");

        assertFailure(RunnerPackageFailure.PACKAGE_NAME_VERSION_MISMATCH, fixture.build(),
                "flowops-executor-0.7.1-linux-amd64.tar.gz");
    }

    @Test
    void rejectsUnsupportedPlatform() {
        // 文件名仍是合法 linux 名（否则先撞文件名预检），由 manifest 声明其它平台
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod()
                .os("windows")
                .fileName("flowops-executor-0.7.0-linux-amd64.tar.gz");

        assertFailure(RunnerPackageFailure.PACKAGE_PLATFORM_UNSUPPORTED, fixture.build(),
                "flowops-executor-0.7.0-linux-amd64.tar.gz");
    }

    @Test
    void rejectsUnsupportedFormatVersion() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod().formatVersion(2);

        assertFailure(RunnerPackageFailure.MANIFEST_FORMAT_UNSUPPORTED, fixture.build(),
                fixture.expectedFileName());
    }

    // ==================== 拒绝：凭据夹带（P3-C） ====================

    @Test
    void rejectsExtraMemberSuchAsCredentialFile() {
        // 目标机凭据（runnerId/地址/token）只能由阶段 3 写成目标机专属 0600 文件，不得随包分发
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod()
                .extraMember("config/runner-private.yaml",
                        "runner:\n  id: runner-1\n  token: super-secret\n");

        assertFailure(RunnerPackageFailure.MEMBER_INVALID, fixture.build(), fixture.expectedFileName());
    }

    @Test
    void rejectsUnknownManifestFieldSmugglingCredentials() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod()
                .extraManifestField("runner", java.util.Map.of("id", "runner-1", "token", "super-secret"));

        assertFailure(RunnerPackageFailure.MANIFEST_INVALID, fixture.build(), fixture.expectedFileName());
    }

    @Test
    void rejectsUnknownMemberOutsideWhitelist() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod().extraMember("extra.txt", "x");

        assertFailure(RunnerPackageFailure.MEMBER_INVALID, fixture.build(), fixture.expectedFileName());
    }

    // ==================== 拒绝：成员集合与属性 ====================

    @Test
    void rejectsMissingRequiredMember() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod()
                .removeMember("images/flowops-executor.tar");

        assertFailure(RunnerPackageFailure.MEMBER_INVALID, fixture.build(), fixture.expectedFileName());
    }

    @Test
    void rejectsDuplicateMember() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        RunnerPackageTestFixture.Entry duplicate = RunnerPackageTestFixture.ofVersion("0.7.0").entry("manifest.json");
        fixture.entries().add(duplicate);

        assertFailure(RunnerPackageFailure.MEMBER_INVALID, fixture.build(), fixture.expectedFileName());
    }

    @Test
    void rejectsWrongMemberMode() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        fixture.entry("bin/flowops-executor").mode("0644");

        assertFailure(RunnerPackageFailure.MEMBER_INVALID, fixture.build(), fixture.expectedFileName());
    }

    @Test
    void rejectsWrongMemberMtime() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        fixture.entry("bin/flowops-executor").mtime(RunnerPackageTestFixture.EPOCH + 1);

        assertFailure(RunnerPackageFailure.MEMBER_INVALID, fixture.build(), fixture.expectedFileName());
    }

    @Test
    void rejectsNonZeroUidOrUserName() {
        RunnerPackageTestFixture nonZeroUid = RunnerPackageTestFixture.prod();
        nonZeroUid.entry("bin/flowops-executor").uid(1000);
        assertFailure(RunnerPackageFailure.MEMBER_INVALID, nonZeroUid.build(), nonZeroUid.expectedFileName());

        RunnerPackageTestFixture namedUser = RunnerPackageTestFixture.prod();
        namedUser.entry("bin/flowops-executor").uname("root");
        assertFailure(RunnerPackageFailure.MEMBER_INVALID, namedUser.build(), namedUser.expectedFileName());
    }

    @Test
    void rejectsNonRegularEntry() {
        // 符号链接成员：主节点只接受普通文件，绝不跟随链接（不解压、不落盘）
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        fixture.entry("manifest.json").type(TarConstants.LF_SYMLINK).link("/etc/passwd");

        assertFailure(RunnerPackageFailure.MEMBER_INVALID, fixture.build(), fixture.expectedFileName());
    }

    @Test
    void rejectsPathTraversalMember() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        fixture.entry("bin/flowops-executor").path("../../etc/cron.d/flowops");

        assertFailure(RunnerPackageFailure.MEMBER_INVALID, fixture.build(), fixture.expectedFileName());
    }

    // ==================== 拒绝：checksums ====================

    @Test
    void rejectsChecksumMismatch() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod()
                .checksumsMutator(bytes -> {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    return text.replaceFirst("^[0-9a-f]", "0".equals(text.substring(0, 1)) ? "1" : "0")
                            .getBytes(StandardCharsets.UTF_8);
                });

        assertFailure(RunnerPackageFailure.CHECKSUM_MISMATCH, fixture.build(), fixture.expectedFileName());
    }

    @Test
    void rejectsChecksumsWithCrlfOrWithoutTrailingNewline() {
        RunnerPackageTestFixture crlf = RunnerPackageTestFixture.prod()
                .checksumsMutator(bytes -> new String(bytes, StandardCharsets.UTF_8)
                        .replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        assertFailure(RunnerPackageFailure.CHECKSUMS_INVALID, crlf.build(), crlf.expectedFileName());

        RunnerPackageTestFixture noTrailing = RunnerPackageTestFixture.prod()
                .checksumsMutator(bytes -> {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    return text.substring(0, text.length() - 1).getBytes(StandardCharsets.UTF_8);
                });
        assertFailure(RunnerPackageFailure.CHECKSUMS_INVALID, noTrailing.build(), noTrailing.expectedFileName());
    }

    @Test
    void rejectsChecksumsNotSortedByPath() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod()
                .checksumsMutator(bytes -> {
                    List<String> lines = new java.util.ArrayList<>(
                            List.of(new String(bytes, StandardCharsets.UTF_8).split("\n")));
                    java.util.Collections.reverse(lines);
                    return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
                });

        assertFailure(RunnerPackageFailure.CHECKSUMS_INVALID, fixture.build(), fixture.expectedFileName());
    }

    // ==================== 拒绝：大小与结构上限 ====================

    @Test
    void rejectsPackageExceedingSizeLimit() {
        RunnerPackageSettings small = new RunnerPackageSettings();
        small.setMaxSize(DataSize.ofBytes(128));

        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        RunnerPackageException e = assertThrows(RunnerPackageException.class,
                () -> validate(small, fixture.build(), fixture.expectedFileName(), tempFile()));

        assertEquals(RunnerPackageFailure.PACKAGE_TOO_LARGE, e.failure());
    }

    @Test
    void rejectsMemberExceedingStructureLimit() {
        RunnerPackageSettings small = new RunnerPackageSettings();
        small.setMaxMemberSize(DataSize.ofBytes(4));

        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        RunnerPackageException e = assertThrows(RunnerPackageException.class,
                () -> validate(small, fixture.build(), fixture.expectedFileName(), tempFile()));

        assertEquals(RunnerPackageFailure.MEMBER_TOO_LARGE, e.failure());
    }

    // ==================== 拒绝：非归档与空包 ====================

    @Test
    void rejectsNonGzipContent() {
        byte[] junk = "this is not a tar.gz".getBytes(StandardCharsets.UTF_8);
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();

        assertFailure(RunnerPackageFailure.ARCHIVE_INVALID, junk, fixture.expectedFileName());
    }

    @Test
    void rejectsEmptyUpload() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();

        assertFailure(RunnerPackageFailure.PACKAGE_EMPTY, new byte[0], fixture.expectedFileName());
    }

    @Test
    void rejectsMissingFileName() {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();

        assertFailure(RunnerPackageFailure.PACKAGE_NAME_MISMATCH, fixture.build(), null);
    }

    // ==================== 工具 ====================

    private Path tempFile() {
        return tempDir.resolve("upload-" + UUID.randomUUID() + ".part");
    }

    private RunnerPackageInspection validate(byte[] bytes, String fileName, Path temp) throws IOException {
        return validate(settings, bytes, fileName, temp);
    }

    private RunnerPackageInspection validate(RunnerPackageSettings usedSettings, byte[] bytes,
                                             String fileName, Path temp) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return new RunnerPackageValidator(usedSettings).validate(in, fileName, temp);
        }
    }

    private void assertFailure(RunnerPackageFailure expected, byte[] bytes, String fileName) {
        RunnerPackageException e = assertThrows(RunnerPackageException.class,
                () -> validate(bytes, fileName, tempFile()));
        assertEquals(expected, e.failure(), "失败原因应为 " + expected + "，实际: " + e.getMessage());
        assertTrue(e.getMessage().startsWith(expected.message()),
                "对外文案应使用固定前缀，实际: " + e.getMessage());
    }
}
