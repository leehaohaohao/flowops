package com.nexa.flowops.service.artifact;

import com.nexa.flowops.common.util.DigestUtil;
import com.nexa.flowops.entity.DeployArtifact;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployArtifactMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.protocol.Artifact.ArtifactChunk;
import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.client.NexaClient;
import com.nexa.protocol.codec.FrameCodec;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主节点 → 子节点「普通文件」传输链路测试（链路连通性自测）。
 *
 * <p>不需要真实 Go 子节点、不需要数据库：
 * <ul>
 *   <li>主节点侧：进程内启动 {@link NexaMaster}，注册放行，收到 {@code ARTIFACT_REQ} 后交给
 *       <b>真实的</b> {@link ArtifactTransferManager}（分块逻辑）+ {@link LocalArtifactStore} 发送</li>
 *   <li>子节点侧：用协议 Java SDK 的 {@link NexaClient} 模拟子节点——注册 → 发 ARTIFACT_REQ →
 *       收 ARTIFACT_DATA 分块 → 重组 → 校验 sha256 与内容</li>
 *   <li>只 mock 数据库访问（Mapper / 注册表查询），协议与分块传输全部走真实代码</li>
 * </ul>
 *
 * <p>运行：{@code ./mvnw test -Dtest=ArtifactTransferLinkTest -DfailIfNoSpecifiedTests=false}
 */
class ArtifactTransferLinkTest {

    private static final String RUNNER_ID = "test-runner-1";
    private static final long SERVICE_ID = 1001L;
    /** 与 ArtifactTransferManager 的 CHUNK_SIZE 保持一致（1MB） */
    private static final int CHUNK_SIZE = 1024 * 1024;

    private NexaMaster master;
    private NexaClient client;
    private Path workDir;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            try {
                client.disconnect("test done");
            } catch (Exception ignored) {
            }
        }
        if (master != null) {
            master.shutdown();
        }
        if (workDir != null && Files.exists(workDir)) {
            try (var paths = Files.walk(workDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void sendSmallPlainFileSingleChunk() throws Exception {
        byte[] content = randomBytes(100 * 1024); // 100KB → 单块
        TransferResult result = transferPlainFile(content);
        assertTransferOk(content, result, 1);
    }

    @Test
    void sendPlainFileAcrossMultipleChunks() throws Exception {
        byte[] content = randomBytes(2 * CHUNK_SIZE + 512 * 1024); // 2.5MB → 3 块
        TransferResult result = transferPlainFile(content);
        assertTransferOk(content, result, 3);
    }

    // ==================== 链路执行 ====================

    /**
     * 走完整链路：主节点分块发送普通文件 → 模拟子节点接收重组
     */
    private TransferResult transferPlainFile(byte[] content) throws Exception {
        workDir = Files.createTempDirectory("flowops-link-test-");
        Path sourceFile = workDir.resolve("plain.bin");
        Files.write(sourceFile, content);
        long sourceSize = content.length;
        String sourceChecksum = DigestUtil.sha256Hex(Files.newInputStream(sourceFile));

        int port = freePort();
        master = startMaster(port, sourceFile, sourceChecksum, sourceSize);

        // ---- 模拟子节点：连接 + 带 token 注册 ----
        client = NexaClient.builder()
                .runnerId(RUNNER_ID)
                .hostname("test-node")
                .ip("127.0.0.1")
                .version("test")
                .build();
        client.connect("127.0.0.1", port);
        client.getSocket().setSoTimeout(15_000);

        Envelope regEnv = ProtocolCodec.buildRegisterRequest(RUNNER_ID, "test-node", "127.0.0.1", "test", "test-token");
        FrameCodec.writeFrame(client.getOutput(), regEnv.toByteArray());
        Envelope regRespEnv = client.readEnvelope();
        assertEquals(MessageType.REGISTER_RESP, regRespEnv.getType(), "注册响应类型不符");
        RegisterResponse regResp = ProtocolCodec.parseRegisterResponse(regRespEnv.getPayload().toByteArray());
        assertTrue(regResp.getSuccess(), "注册失败: " + regResp.getMessage());
        System.out.println("[link-test] 子节点注册成功: " + regResp.getMessage());

        // ---- 发起产物请求（普通文件，JAR 类型）----
        ArtifactRequest req = ArtifactRequest.newBuilder()
                .setServiceId(String.valueOf(SERVICE_ID))
                .setType("JAR")
                .setVersion(0) // 0 = 最新
                .build();
        Envelope reqEnv = ProtocolCodec.buildArtifactRequest(RUNNER_ID, req);
        String transferId = reqEnv.getRequestId();
        FrameCodec.writeFrame(client.getOutput(), reqEnv.toByteArray());
        System.out.println("[link-test] 已发送 ARTIFACT_REQ: transferId=" + transferId + ", 文件大小=" + sourceSize + "B");

        // ---- 接收分块并重组 ----
        Path receivedFile = workDir.resolve("received.bin");
        int chunks = 0;
        long reportedTotalSize = -1;
        String lastChunkChecksum = null;
        boolean transferIdMatched = true;
        try (OutputStream out = Files.newOutputStream(receivedFile)) {
            while (true) {
                Envelope env = client.readEnvelope();
                if (env.getType() != MessageType.ARTIFACT_DATA) {
                    System.out.println("[link-test] 忽略非数据帧: " + env.getType());
                    continue;
                }
                if (!transferId.equals(env.getRequestId())) {
                    transferIdMatched = false;
                }
                ArtifactChunk chunk = ProtocolCodec.parseArtifactChunk(env.getPayload().toByteArray());
                out.write(chunk.getData().toByteArray());
                chunks++;
                reportedTotalSize = chunk.getTotalSize();
                System.out.printf("[link-test] 收到分块 %d/%d, %d bytes%n",
                        chunk.getSequence() + 1, chunk.getTotalChunks(), chunk.getData().size());
                if (chunk.getSequence() == chunk.getTotalChunks() - 1) {
                    lastChunkChecksum = chunk.getChecksum();
                    break;
                }
            }
        }

        byte[] received = Files.readAllBytes(receivedFile);
        System.out.println("[link-test] 重组完成: " + received.length + "B, 校验和=" + lastChunkChecksum);
        return new TransferResult(chunks, received, lastChunkChecksum, reportedTotalSize, transferIdMatched);
    }

    private void assertTransferOk(byte[] content, TransferResult result, int expectedChunks) throws IOException {
        assertEquals(expectedChunks, result.chunks(), "分块数不符（分块大小 1MB）");
        assertTrue(result.transferIdMatched(), "分块 request_id 未复用请求的 transfer_id");
        assertEquals(content.length, result.reportedTotalSize(), "末块上报的总大小不符");
        assertEquals(content.length, result.received().length, "重组文件大小不符");
        assertArrayEquals(content, result.received(), "重组文件内容与原文件不一致");
        String expectedChecksum = DigestUtil.sha256Hex(content);
        assertEquals(expectedChecksum, result.lastChunkChecksum(), "末块 sha256 校验和不符");
        System.out.println("[link-test] ✅ 链路打通：文件完整、sha256 校验通过");
    }

    // ==================== 主节点（进程内） ====================

    /**
     * 启动进程内主节点：注册放行 + 真实 ArtifactTransferManager 分块发送指定文件。
     * 仅 mock 数据库访问（DeployServiceMapper / DeployArtifactMapper）。
     */
    private NexaMaster startMaster(int port, Path sourceFile, String checksum, long size) throws Exception {
        // 产物注册表：mock mapper 返回指向该普通文件的元数据
        DeployArtifact artifact = new DeployArtifact();
        artifact.setId(1L);
        artifact.setServiceId(SERVICE_ID);
        artifact.setDeployName("link-test");
        artifact.setType("JAR");
        artifact.setFileName(sourceFile.getFileName().toString());
        artifact.setStoragePath(sourceFile.toAbsolutePath().toString());
        artifact.setSize(size);
        artifact.setChecksum(checksum);
        artifact.setVersion(1);
        DeployArtifactMapper artifactMapper = mock(DeployArtifactMapper.class);
        when(artifactMapper.selectOne(any())).thenReturn(artifact);
        ArtifactRegistry registry = new ArtifactRegistry(artifactMapper);

        // 服务：nodeId 指向该 runner（L2 归属校验通过）
        DeployService service = new DeployService();
        service.setId(SERVICE_ID);
        service.setName("link-test");
        service.setDeployName("link-test");
        service.setNodeId(RUNNER_ID);
        service.setVolumeDir(sourceFile.getParent().toAbsolutePath().toString());
        DeployServiceMapper serviceMapper = mock(DeployServiceMapper.class);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(service);

        AtomicReference<NexaMaster> masterRef = new AtomicReference<>();
        ObjectProvider<NexaMaster> provider = fixedProvider(masterRef);
        ArtifactTransferManager transferManager =
                new ArtifactTransferManager(registry, new LocalArtifactStore(), serviceMapper, provider);

        NexaMasterListener listener = new NexaMasterListener() {
            @Override
            public RegisterResponse onRegister(RunnerSession session, RegisterRequest req) {
                System.out.println("[link-test] 主节点收到注册: runnerId=" + req.getRunnerId()
                        + ", token=" + (req.getToken().isEmpty() ? "(空)" : "(已携带)"));
                return RegisterResponse.newBuilder().setSuccess(true).setMessage("ok").build();
            }

            @Override
            public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
            }

            @Override
            public void onDisconnect(String runnerId, String reason) {
            }

            @Override
            public void onArtifactRequest(RunnerSession session, Envelope requestEnvelope, ArtifactRequest req) {
                System.out.println("[link-test] 主节点收到 ARTIFACT_REQ: serviceId=" + req.getServiceId()
                        + ", type=" + req.getType() + ", version=" + req.getVersion());
                transferManager.handleArtifactRequest(session, requestEnvelope, req);
            }
        };

        NexaMaster master = NexaMaster.builder(listener)
                .host("127.0.0.1")
                .port(port)
                .heartbeatTimeout(Duration.ofSeconds(30))
                .heartbeatCheckInterval(Duration.ofSeconds(5))
                .build();
        master.start();
        masterRef.set(master);
        System.out.println("[link-test] 主节点已启动: 127.0.0.1:" + port);
        return master;
    }

    /** 供 ArtifactTransferManager 惰性取 NexaMaster 的简易 ObjectProvider */
    private static ObjectProvider<NexaMaster> fixedProvider(AtomicReference<NexaMaster> ref) {
        return new ObjectProvider<>() {
            @Override
            public NexaMaster getObject() {
                return ref.get();
            }

            @Override
            public NexaMaster getObject(Object... args) {
                return ref.get();
            }

            @Override
            public NexaMaster getIfAvailable() {
                return ref.get();
            }

            @Override
            public NexaMaster getIfUnique() {
                return ref.get();
            }

            @Override
            public Iterator<NexaMaster> iterator() {
                NexaMaster current = ref.get();
                return current == null ? List.<NexaMaster>of().iterator() : List.of(current).iterator();
            }
        };
    }

    // ==================== 工具 ====================

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record TransferResult(int chunks, byte[] received, String lastChunkChecksum,
                                  long reportedTotalSize, boolean transferIdMatched) {
    }
}
