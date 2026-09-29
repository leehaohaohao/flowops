package com.nexa.flowops.service.nodepackage;

import com.nexa.flowops.dto.RunnerPackageVO;
import com.nexa.flowops.entity.NexaNodePackage;
import com.nexa.flowops.mapper.NexaNodePackageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传与查询行为测试（阶段 2 P3 的 P3 行 + 审阅 B2 行）：
 * R2 的五个上传分支（全新 / 健康复用 / 修复文件 / 补登记 / 损坏替换）、并发同摘要、登记失败自愈、
 * 失败清理、查询与错误码，以及存储层"内容等于摘要"的判据。
 */
class RunnerPackageUploadServiceTest {

    @TempDir
    Path storeDir;

    private NexaNodePackageMapper mapper;
    private RunnerPackageSettings settings;
    private RunnerPackageService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NexaNodePackageMapper.class);
        settings = new RunnerPackageSettings();
        settings.setStoreDir(storeDir.toString());
        service = new RunnerPackageService(new RunnerPackageValidator(settings),
                new LocalRunnerPackageStore(settings), new RunnerPackageRegistry(mapper), settings);
    }

    // ==================== R2 分支 1：全新 ====================

    @Test
    void uploadStoresPackageImmutablyAndRegistersIndex() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        String sha256 = fixture.sha256();

        RunnerPackageService.UploadOutcome outcome = service.upload(
                multipart(fixture.expectedFileName(), bytes), "admin");

        assertFalse(outcome.existing());
        assertFalse(outcome.repaired());
        assertEquals("发布包已上传", outcome.message());
        RunnerPackageVO vo = outcome.packageInfo();
        assertEquals(sha256, vo.getSha256());
        assertEquals("0.7.0", vo.getVersion());
        assertEquals((long) bytes.length, vo.getSizeBytes());
        assertEquals("admin", vo.getUploadedBy());
        assertTrue(Files.isRegularFile(storeDir.resolve(sha256 + ".tar.gz")), "包应按摘要命名入不可变存储");
        assertSameContent(storeDir.resolve(sha256 + ".tar.gz"), bytes);

        ArgumentCaptor<NexaNodePackage> captor = ArgumentCaptor.forClass(NexaNodePackage.class);
        verify(mapper).insert(captor.capture());
        assertEquals(sha256, captor.getValue().getSha256());
        assertEquals("flowops-executor:0.7.0", captor.getValue().getImageReference());
        assertNoTempFiles();
    }

    @Test
    void acceptsDevPackageTooAndKeepsSeparateDigest() throws Exception {
        RunnerPackageTestFixture dev = RunnerPackageTestFixture.dev();
        byte[] bytes = dev.build();

        RunnerPackageService.UploadOutcome outcome = service.upload(
                multipart(dev.expectedFileName(), bytes), "admin");

        assertEquals("0.7.0-dev", outcome.packageInfo().getVersion());
        assertTrue(Files.isRegularFile(storeDir.resolve(dev.sha256() + ".tar.gz")));
    }

    // ==================== R2 分支 2：健康复用（不覆盖健康文件） ====================

    @Test
    void duplicateUploadWithHealthyFileKeepsItUntouched() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        Path stored = writeStoredFile(fixture.sha256(), bytes);
        FileTime modifiedBefore = Files.getLastModifiedTime(stored);
        when(mapper.selectById(fixture.sha256())).thenReturn(indexRow(fixture.sha256(), bytes.length));

        RunnerPackageService.UploadOutcome outcome = service.upload(
                multipart(fixture.expectedFileName(), bytes), "admin");

        assertTrue(outcome.existing(), "相同摘要重复上传应为幂等成功");
        assertFalse(outcome.repaired(), "健康文件不得被重写");
        assertEquals("发布包已存在（相同摘要）", outcome.message());
        assertSameContent(stored, bytes);
        assertEquals(modifiedBefore, Files.getLastModifiedTime(stored), "健康文件的修改时间不应变化");
        verify(mapper, never()).insert(any());
        assertNoTempFiles();
    }

    // ==================== R2 分支 3：修复文件 ====================

    @Test
    void duplicateUploadRepairsMissingFile() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        Path stored = storeDir.resolve(fixture.sha256() + ".tar.gz");
        assertFalse(Files.exists(stored), "前置：数据库有行但文件缺失");
        when(mapper.selectById(fixture.sha256())).thenReturn(indexRow(fixture.sha256(), bytes.length));

        RunnerPackageService.UploadOutcome outcome = service.upload(
                multipart(fixture.expectedFileName(), bytes), "admin");

        assertTrue(outcome.existing());
        assertTrue(outcome.repaired(), "缺失文件应被本次上传修复");
        assertEquals("发布包已存在，已按摘要修复存储文件", outcome.message());
        assertSameContent(stored, bytes);
        verify(mapper, never()).insert(any(NexaNodePackage.class));
        assertNoTempFiles();
    }

    @Test
    void duplicateUploadReplacesCorruptFile() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        Path stored = writeStoredFile(fixture.sha256(), "tampered-content".getBytes());
        when(mapper.selectById(fixture.sha256())).thenReturn(indexRow(fixture.sha256(), bytes.length));

        RunnerPackageService.UploadOutcome outcome = service.upload(
                multipart(fixture.expectedFileName(), bytes), "admin");

        assertTrue(outcome.existing());
        assertTrue(outcome.repaired());
        assertEquals("发布包已存在，已按摘要修复存储文件", outcome.message());
        assertSameContent(stored, bytes);
        assertNoTempFiles();
    }

    // ==================== R2 分支 4/5：补登记与损坏替换 ====================

    @Test
    void uploadBackfillsIndexWhenFileExistsWithoutRow() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        Path stored = writeStoredFile(fixture.sha256(), bytes);
        FileTime modifiedBefore = Files.getLastModifiedTime(stored);
        when(mapper.selectById(fixture.sha256())).thenReturn(null);

        RunnerPackageService.UploadOutcome outcome = service.upload(
                multipart(fixture.expectedFileName(), bytes), "admin");

        assertFalse(outcome.existing(), "索引行此前不存在");
        assertFalse(outcome.repaired(), "文件健康则只补登记，不重写");
        assertEquals("发布包已补登记（文件已存在且摘要一致）", outcome.message());
        assertSameContent(stored, bytes);
        assertEquals(modifiedBefore, Files.getLastModifiedTime(stored));
        verify(mapper).insert(any(NexaNodePackage.class));
        assertNoTempFiles();
    }

    @Test
    void uploadReplacesCorruptFileWithoutIndexAndRegisters() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        Path stored = writeStoredFile(fixture.sha256(), "damaged".getBytes());
        when(mapper.selectById(fixture.sha256())).thenReturn(null);

        RunnerPackageService.UploadOutcome outcome = service.upload(
                multipart(fixture.expectedFileName(), bytes), "admin");

        assertFalse(outcome.existing());
        assertTrue(outcome.repaired(), "同名文件内容不符应被原子替换");
        assertEquals("发布包已上传", outcome.message());
        assertSameContent(stored, bytes);
        verify(mapper).insert(any(NexaNodePackage.class));
    }

    // ==================== R2 补充规则：登记失败自愈与并发 ====================

    @Test
    void registrationFailureKeepsFileSoNextUploadBackfills() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        when(mapper.selectById(fixture.sha256())).thenReturn(null);
        when(mapper.insert(any(NexaNodePackage.class))).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> service.upload(multipart(fixture.expectedFileName(), bytes), "admin"));

        // 文件已原子落盘：下一次同摘要上传走"补登记"，不需要重新传输即可自愈
        Path stored = storeDir.resolve(fixture.sha256() + ".tar.gz");
        assertSameContent(stored, bytes);
        assertNoTempFiles();

        when(mapper.insert(any(NexaNodePackage.class))).thenReturn(1);
        RunnerPackageService.UploadOutcome retry = service.upload(
                multipart(fixture.expectedFileName(), bytes), "admin");
        assertEquals("发布包已补登记（文件已存在且摘要一致）", retry.message());
        assertSameContent(stored, bytes);
    }

    @Test
    void concurrentSameDigestUploadsBothSucceedWithOneIndexRow() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        String sha256 = fixture.sha256();
        AtomicInteger inserts = new AtomicInteger();
        when(mapper.insert(any(NexaNodePackage.class))).thenAnswer(invocation -> {
            if (inserts.incrementAndGet() > 1) {
                throw new DuplicateKeyException("duplicate sha256");
            }
            return 1;
        });
        // 首次查无索引；冲突方回读既有行（顺序：null → 既有行）
        when(mapper.selectById(sha256)).thenReturn(null, indexRow(sha256, bytes.length));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RunnerPackageService.UploadOutcome> first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.upload(multipart(fixture.expectedFileName(), bytes), "admin-a");
            });
            Future<RunnerPackageService.UploadOutcome> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.upload(multipart(fixture.expectedFileName(), bytes), "admin-b");
            });
            start.countDown();
            RunnerPackageService.UploadOutcome one = first.get(15, TimeUnit.SECONDS);
            RunnerPackageService.UploadOutcome two = second.get(15, TimeUnit.SECONDS);

            assertEquals(sha256, one.packageInfo().getSha256());
            assertEquals(sha256, two.packageInfo().getSha256());
            assertTrue(inserts.get() >= 1, "至少写入一次索引");
        } finally {
            pool.shutdownNow();
        }
        assertSameContent(storeDir.resolve(sha256 + ".tar.gz"), bytes);
        assertNoTempFiles();
    }

    // ==================== 拒绝路径 ====================

    @Test
    void rejectsTamperedPackageAndCleansTempFile() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod()
                .extraMember("config/runner-private.yaml", "runner:\n  token: secret\n");

        RunnerPackageException e = assertThrows(RunnerPackageException.class,
                () -> service.upload(multipart(fixture.expectedFileName(), fixture.build()), "admin"));

        assertEquals(RunnerPackageFailure.MEMBER_INVALID, e.failure());
        verify(mapper, never()).insert(any(NexaNodePackage.class));
        assertNoTempFiles();
        assertEquals(0, countPackages(), "失败包不得进入不可变存储");
    }

    @Test
    void rejectsBadFileNameAndOversizeBeforeReading() throws Exception {
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();

        RunnerPackageException badName = assertThrows(RunnerPackageException.class,
                () -> service.upload(multipart("flowops-executor-0.7.0-linux-arm64.tar.gz", fixture.build()), "admin"));
        assertEquals(RunnerPackageFailure.PACKAGE_NAME_MISMATCH, badName.failure());

        settings.setMaxSize(DataSize.ofBytes(64));
        RunnerPackageException tooLarge = assertThrows(RunnerPackageException.class,
                () -> service.upload(multipart(fixture.expectedFileName(), fixture.build()), "admin"));
        assertEquals(RunnerPackageFailure.PACKAGE_TOO_LARGE, tooLarge.failure());

        RunnerPackageException empty = assertThrows(RunnerPackageException.class,
                () -> service.upload(multipart(fixture.expectedFileName(), new byte[0]), "admin"));
        assertEquals(RunnerPackageFailure.PACKAGE_EMPTY, empty.failure());
        verify(mapper, never()).insert(any(NexaNodePackage.class));
        assertNoTempFiles();
    }

    // ==================== 查询 ====================

    @Test
    void listsAndReadsStoredPackages() {
        NexaNodePackage entity = indexRow("a".repeat(64), 993L);
        when(mapper.selectList(any())).thenReturn(List.of(entity));
        when(mapper.selectById("a".repeat(64))).thenReturn(entity);

        List<RunnerPackageVO> list = service.list();
        assertEquals(1, list.size());
        assertEquals("0.7.0", list.get(0).getVersion());
        assertNull(list.get(0).getExisting(), "列表响应不带 existing 标记");
        assertNull(list.get(0).getRepaired(), "列表响应不带 repaired 标记");

        RunnerPackageVO detail = service.get("a".repeat(64));
        assertEquals("flowops-executor-0.7.0-linux-amd64.tar.gz", detail.getFileName());
    }

    @Test
    void rejectsInvalidOrUnknownDigest() {
        RunnerPackageException invalid = assertThrows(RunnerPackageException.class,
                () -> service.get("not-a-digest"));
        assertEquals(RunnerPackageFailure.SHA256_INVALID, invalid.failure());

        RunnerPackageException missing = assertThrows(RunnerPackageException.class,
                () -> service.get("b".repeat(64)));
        assertEquals(RunnerPackageFailure.PACKAGE_NOT_FOUND, missing.failure());
        assertTrue(missing.getMessage().contains("b".repeat(64)));
    }

    // ==================== 存储层判据 ====================

    @Test
    void storeNeverOverwritesExistingPackageAndCleansTempFiles() throws Exception {
        LocalRunnerPackageStore store = new LocalRunnerPackageStore(settings);
        String sha256 = "c".repeat(64);
        Path stored = storeDir.resolve(sha256 + ".tar.gz");
        Files.writeString(stored, "existing-content");

        Path temp = store.createTempFile();
        Files.writeString(temp, "new-content");
        Path committed = store.commit(temp, sha256);

        assertEquals(stored, committed);
        assertEquals("existing-content", Files.readString(stored), "同摘要文件不得被覆盖");
        assertFalse(Files.exists(temp), "提交后临时文件应被删除");

        Path stale = store.createTempFile();
        assertEquals(1, store.cleanupTempFiles());
        assertFalse(Files.exists(stale));
    }

    @Test
    void storeDetectsMissingOrCorruptFileAndReplacesAtomically() throws Exception {
        LocalRunnerPackageStore store = new LocalRunnerPackageStore(settings);
        RunnerPackageTestFixture fixture = RunnerPackageTestFixture.prod();
        byte[] bytes = fixture.build();
        String sha256 = fixture.sha256();

        assertFalse(store.isHealthy(sha256, (long) bytes.length), "文件缺失不算健康");

        Path stored = writeStoredFile(sha256, "corrupt".getBytes());
        assertFalse(store.isHealthy(sha256, (long) bytes.length), "内容不符不算健康");
        assertFalse(store.isHealthy(sha256, 1L), "大小不符不算健康");

        Path temp = store.createTempFile();
        Files.write(temp, bytes);
        store.commitReplacing(temp, sha256);
        assertTrue(store.isHealthy(sha256, (long) bytes.length), "替换后应恢复健康");
        assertSameContent(stored, bytes);
    }

    @Test
    void storeRejectsNonHexDigestToPreventPathTraversal() {
        LocalRunnerPackageStore store = new LocalRunnerPackageStore(settings);

        assertThrows(RunnerPackageException.class, () -> store.packagePath("../../etc/passwd"));
        assertThrows(RunnerPackageException.class, () -> store.packagePath("A".repeat(64)));
        assertThrows(RunnerPackageException.class, () -> store.isHealthy("../x", 1L));
    }

    // ==================== 工具 ====================

    private MockMultipartFile multipart(String fileName, byte[] content) {
        return new MockMultipartFile("file", fileName, "application/gzip", content);
    }

    private NexaNodePackage indexRow(String sha256, long sizeBytes) {
        NexaNodePackage entity = new NexaNodePackage();
        entity.setSha256(sha256);
        entity.setFileName("flowops-executor-0.7.0-linux-amd64.tar.gz");
        entity.setVersion("0.7.0");
        entity.setSizeBytes(sizeBytes);
        return entity;
    }

    private Path writeStoredFile(String sha256, byte[] content) throws Exception {
        Path stored = storeDir.resolve(sha256 + ".tar.gz");
        Files.write(stored, content);
        return stored;
    }

    private void assertNoTempFiles() throws Exception {
        try (Stream<Path> files = Files.list(storeDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".upload-")),
                    "失败或成功路径都不应残留 .upload-*.part");
        }
    }

    private long countPackages() throws Exception {
        try (Stream<Path> files = Files.list(storeDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".tar.gz")).count();
        }
    }

    private void assertSameContent(Path path, byte[] expected) throws Exception {
        assertTrue(Files.isRegularFile(path), "文件应存在: " + path.getFileName());
        assertTrue(java.util.Arrays.equals(expected, Files.readAllBytes(path)),
                "存储内容应与上传字节完全一致: " + path.getFileName());
    }
}
