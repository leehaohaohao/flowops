package com.nexa.flowops.service.artifact;

import com.google.protobuf.ByteString;
import com.nexa.flowops.entity.DeployArtifact;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.protocol.Artifact.ArtifactAck;
import com.nexa.protocol.Artifact.ArtifactChunk;
import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.RunnerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端产物传输：收到子节点 ARTIFACT_REQ → 校验服务归属（L2）→ 查注册表 → 流式分块下发
 * ARTIFACT_DATA（复用请求 request_id 作为 transfer_id），出错时回 ARTIFACT_ACK(ok=false)。
 * 传输在独立线程池执行，避免阻塞 Netty IO 线程。
 */
@Component
public class ArtifactTransferManager {

    private static final Logger log = LoggerFactory.getLogger(ArtifactTransferManager.class);

    /** 单块大小（远小于帧上限 10MB，内存有界） */
    private static final int CHUNK_SIZE = 1024 * 1024;

    private final ArtifactRegistry artifactRegistry;
    private final ArtifactStore artifactStore;
    private final DeployServiceMapper serviceMapper;
    private final ObjectProvider<NexaMaster> nexaMasterProvider;

    private final ExecutorService transferExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "artifact-transfer-");
        t.setDaemon(true);
        return t;
    });

    public ArtifactTransferManager(ArtifactRegistry artifactRegistry,
                                   ArtifactStore artifactStore,
                                   DeployServiceMapper serviceMapper,
                                   ObjectProvider<NexaMaster> nexaMasterProvider) {
        this.artifactRegistry = artifactRegistry;
        this.artifactStore = artifactStore;
        this.serviceMapper = serviceMapper;
        this.nexaMasterProvider = nexaMasterProvider;
    }

    private NexaMaster nexaMaster() {
        return nexaMasterProvider.getObject();
    }

    /**
     * ARTIFACT_REQ 入口（FlowOpsMasterListener 调用，Netty IO 线程）：
     * 转交独立线程池执行，防止大产物阻塞消息循环
     */
    public void handleArtifactRequest(RunnerSession session, Envelope requestEnvelope, ArtifactRequest req) {
        transferExecutor.execute(() -> doTransfer(session, requestEnvelope, req));
    }

    private void doTransfer(RunnerSession session, Envelope requestEnvelope, ArtifactRequest req) {
        String runnerId = session.getRunnerId();
        String requestId = requestEnvelope.getRequestId();
        try {
            DeployService service = serviceMapper.selectById(Long.parseLong(req.getServiceId()));
            if (service == null) {
                sendFail(runnerId, requestId, "服务不存在: " + req.getServiceId());
                return;
            }
            // L2：仅允许请求分配给该节点的服务（nodeId=runnerId 或 auto 已指派）
            if (!isAssignedTo(service, runnerId)) {
                log.warn("[Master] 拒绝越权产物请求: runnerId={}, serviceId={}", runnerId, req.getServiceId());
                sendFail(runnerId, requestId, "无权访问该服务产物");
                return;
            }

            String type = req.getType() == null ? "" : req.getType().trim().toUpperCase();
            DeployArtifact artifact = req.getVersion() > 0
                    ? artifactRegistry.findByVersion(service.getId(), type, req.getVersion())
                    : artifactRegistry.findLatest(service.getId(), type);
            if (artifact == null) {
                log.warn("[Master] 产物不存在: serviceId={}, type={}, version={}",
                        service.getId(), type, req.getVersion());
                sendFail(runnerId, requestId, "产物不存在: type=" + type + ", version=" + req.getVersion());
                return;
            }

            streamChunks(runnerId, requestId, artifact);
        } catch (Exception e) {
            log.error("[Master] 产物传输异常: runnerId={}, requestId={}, err={}", runnerId, requestId, e.getMessage(), e);
            sendFail(runnerId, requestId, "产物传输异常: " + e.getMessage());
        }
    }

    private boolean isAssignedTo(DeployService service, String runnerId) {
        String nodeId = service.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            return false;
        }
        String trimmed = nodeId.trim();
        return trimmed.equalsIgnoreCase(runnerId) || trimmed.equalsIgnoreCase("auto");
    }

    private void streamChunks(String runnerId, String requestId, DeployArtifact artifact) throws Exception {
        try (ArtifactStore.PreparedArtifact prepared = artifactStore.prepare(artifact)) {
            long totalSize = prepared.size();
            int totalChunks = (int) ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
            if (totalChunks == 0) {
                totalChunks = 1;
            }
            byte[] buf = new byte[CHUNK_SIZE];
            InputStream in = prepared.stream();
            long start = System.currentTimeMillis();

            for (int seq = 0; seq < totalChunks; seq++) {
                int read = 0;
                while (read < CHUNK_SIZE) {
                    int n = in.read(buf, read, CHUNK_SIZE - read);
                    if (n == -1) {
                        break;
                    }
                    read += n;
                }
                boolean last = (seq == totalChunks - 1);
                ArtifactChunk chunk = ArtifactChunk.newBuilder()
                        .setTransferId(requestId)
                        .setSequence(seq)
                        .setTotalChunks(totalChunks)
                        .setTotalSize(totalSize)
                        .setChecksum(last ? prepared.checksum() : "")
                        .setData(ByteString.copyFrom(buf, 0, read))
                        .build();
                Envelope envelope = ProtocolCodec.buildArtifactChunk(requestId, runnerId, chunk);
                if (!nexaMaster().sendTo(runnerId, envelope)) {
                    log.warn("[Master] 产物分块发送失败（节点不可写），中止传输: runnerId={}, chunk={}/{}",
                            runnerId, seq + 1, totalChunks);
                    return;
                }
            }
            log.info("[Master] 产物传输完成: runnerId={}, serviceId={}, type={}, version={}, size={}B, chunks={}, 耗时={}ms",
                    runnerId, artifact.getServiceId(), artifact.getType(), artifact.getVersion(),
                    totalSize, totalChunks, System.currentTimeMillis() - start);
        }
    }

    /**
     * 传输失败回执（master → runner，ARTIFACT_ACK ok=false）。
     * 协议 codec 的 buildArtifactAck 固定 runner→master 方向，此处手工构造主→从信封。
     */
    private void sendFail(String runnerId, String requestId, String message) {
        try {
            ArtifactAck ack = ArtifactAck.newBuilder().setOk(false).setError(message).build();
            Envelope envelope = Envelope.newBuilder()
                    .setVersion(1)
                    .setType(MessageType.ARTIFACT_ACK)
                    .setRequestId(requestId)
                    .setSourceId("master")
                    .setTargetId(runnerId)
                    .setTimestamp(System.currentTimeMillis())
                    .setPayload(ByteString.copyFrom(ack.toByteArray()))
                    .build();
            nexaMaster().sendTo(runnerId, envelope);
            log.info("[Master] 已回传产物失败回执: runnerId={}, requestId={}, err={}", runnerId, requestId, message);
        } catch (Exception e) {
            log.warn("[Master] 发送产物失败回执异常: runnerId={}, err={}", runnerId, e.getMessage());
        }
    }
}
