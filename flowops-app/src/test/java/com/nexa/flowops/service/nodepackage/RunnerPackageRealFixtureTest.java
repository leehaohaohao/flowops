package com.nexa.flowops.service.nodepackage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨仓兼容性回归（阶段 2 P3-C）：主节点校验器必须接受**执行器仓库真实产出**的格式 v1 发布包，
 * 且 prod 与 dev 两种环境包都能通过、各自独立摘要、不含任何节点凭据。
 *
 * <p>固件来源与重新生成方式见 {@code src/test/resources/runner-package/README.md}：
 * 两个包由 Go 侧 {@code deploy/packager package} 生成并经 {@code packager verify} 自检通过。
 */
class RunnerPackageRealFixtureTest {

    private static final String PROD_FIXTURE = "runner-package/flowops-executor-0.7.0-linux-amd64.tar.gz";
    private static final String DEV_FIXTURE = "runner-package/flowops-executor-0.7.0-dev-linux-amd64.tar.gz";
    private static final String PROD_SHA256 = "02c16ee9800cc57c0a0dd8693747debc8705d274508d96ff0067ff7e818376ab";
    private static final String DEV_SHA256 = "6b915df70c8b855d382f1faf4e9a02e4fb09991340ecb8c4731806294f7c9230";
    private static final String IMAGE_ID =
            "sha256:9f2c1e0b7a4d5c8e6f3a2b1d0c9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e";

    @TempDir
    Path tempDir;

    @Test
    void acceptsRealProdPackageFromExecutorRepository() throws Exception {
        RunnerPackageInspection inspection = validateFixture(PROD_FIXTURE);

        assertEquals(PROD_SHA256, inspection.sha256(), "prod 固件摘要应与 Go 侧 verify 输出一致");
        assertEquals("0.7.0", inspection.version());
        assertEquals("linux", inspection.os());
        assertEquals("amd64", inspection.arch());
        assertEquals(1, inspection.formatVersion());
        assertEquals("4786ea0020f471c6ddf53ebef098490ad7f641b6", inspection.gitCommit());
        assertEquals(1758931200L, inspection.sourceDateEpoch());
        assertEquals("flowops-executor:0.7.0", inspection.imageReference());
        assertEquals(IMAGE_ID, inspection.imageId());
        assertEquals("27.3.1", inspection.dockerCliVersion());
        assertEquals("v2.29.7", inspection.composeVersion());
        assertEquals(expectedMembers(), inspection.members());
    }

    @Test
    void acceptsRealDevPackageFromExecutorRepository() throws Exception {
        RunnerPackageInspection inspection = validateFixture(DEV_FIXTURE);

        assertEquals(DEV_SHA256, inspection.sha256());
        assertEquals("0.7.0-dev", inspection.version(), "dev 包版本带 -dev 后缀（契约 §11）");
        assertEquals("flowops-executor:0.7.0-dev", inspection.imageReference());
        assertEquals(6, inspection.members().size());
    }

    @Test
    void prodAndDevFixturesHaveIndependentDigests() {
        assertNotEquals(PROD_SHA256, DEV_SHA256);
    }

    @Test
    void realPackagesCarryNoNodeCredentials() throws Exception {
        // P3-C：通用包与目标机凭据分离——包内只有四个载荷成员，没有配置文件、私钥或凭据文件
        for (String fixture : List.of(PROD_FIXTURE, DEV_FIXTURE)) {
            RunnerPackageInspection inspection = validateFixture(fixture);
            assertTrue(inspection.members().stream().noneMatch(path -> path.contains("config")
                            || path.contains("private") || path.contains("ssh") || path.contains("env")),
                    fixture + " 不应包含凭据或环境配置文件成员");
            assertEquals(expectedMembers(), inspection.members());
        }
    }

    private RunnerPackageInspection validateFixture(String resource) throws IOException {
        Path temp = tempDir.resolve(Path.of(resource).getFileName().toString() + ".part");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "缺少测试固件: " + resource);
            return new RunnerPackageValidator(new RunnerPackageSettings())
                    .validate(in, Path.of(resource).getFileName().toString(), temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static List<String> expectedMembers() {
        return List.of("bin/flowops-executor", "checksums.txt", "images/flowops-executor.tar",
                "manifest.json", "templates/docker-compose.yaml", "templates/flowops-executor.service");
    }
}
