package com.nexa.flowops.service.node;

import com.nexa.protocol.Query.ContainerStatusRequest;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.master.RunnerSession;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 进行中查询的会话归因测试（D.2）：
 * 掉线清理只失败化“发起的会话代次 == 事件所属代次”的查询；
 * 非匹配代次或身份未知（代次 0）时保留等待，由查询自身超时兜底。
 */
class QueryManagerTest {

    private static final String RUNNER_ID = "runner-1";

    @Test
    void oldDisconnectDuringSendCannotFailSuccessorQuery() throws Exception {
        NodeService nodeService = mock(NodeService.class);
        RunnerSession successor = mock(RunnerSession.class);
        when(nodeService.getCurrentTarget(RUNNER_ID))
                .thenReturn(Optional.of(new NodeService.SessionTarget(successor, 2L)));
        when(nodeService.withRunnerLock(org.mockito.ArgumentMatchers.eq(RUNNER_ID), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        QueryManager queryManager = new QueryManager(nodeService);
        AtomicReference<Boolean> survivedOldDisconnect = new AtomicReference<>(false);
        when(successor.send(any())).thenAnswer(inv -> {
            queryManager.failPendingForNode(RUNNER_ID, 1L, "old-session-lost");
            survivedOldDisconnect.set(true);
            return true;
        });

        ContainerStatusRequest req = ContainerStatusRequest.newBuilder().setServiceId("1001").build();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ContainerStatusResponse>> result =
                    pool.submit(() -> queryManager.queryContainerStatus(RUNNER_ID, req, 5000));
            for (int i = 0; i < 100 && !survivedOldDisconnect.get(); i++) {
                Thread.sleep(10);
            }
            assertTrue(survivedOldDisconnect.get(), "查询应发送到接管者连接");
            assertFalse(result.isDone(), "旧会话断开不得结束接管者的查询");
            queryManager.onContainerStatus(
                    new RunnerSession(RUNNER_ID, new EmbeddedChannel(), "new", "127.0.0.1", "0.6.2"),
                    ContainerStatusResponse.newBuilder().setRunning(true).build());
            assertTrue(result.get(2, TimeUnit.SECONDS).isPresent());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failPendingForNode_onlyFailsMatchingGeneration() throws Exception {
        NodeService nodeService = mock(NodeService.class);
        RunnerSession target = mock(RunnerSession.class);
        when(target.send(any())).thenReturn(true);
        when(nodeService.getCurrentTarget(RUNNER_ID))
                .thenReturn(Optional.of(new NodeService.SessionTarget(target, 7L)));
        when(nodeService.withRunnerLock(org.mockito.ArgumentMatchers.eq(RUNNER_ID), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        QueryManager queryManager = new QueryManager(nodeService);

        ContainerStatusRequest req = ContainerStatusRequest.newBuilder().setServiceId("1001").build();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ContainerStatusResponse>> running =
                    pool.submit(() -> queryManager.queryContainerStatus(RUNNER_ID, req, 5000));
            Thread.sleep(200); // 等待查询下发并挂起
            verify(target).send(any());

            queryManager.failPendingForNode(RUNNER_ID, 8L, "other-generation");
            Thread.sleep(100);
            assertFalse(running.isDone(), "非匹配代次不得失败该查询");

            queryManager.failPendingForNode(RUNNER_ID, 7L, "connection_lost");
            Optional<ContainerStatusResponse> result = running.get(2, TimeUnit.SECONDS);
            assertTrue(result.isEmpty(), "匹配代次应结束该查询并返回空");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failPendingForNode_withUnknownGeneration_keepsQueryWaiting() throws Exception {
        NodeService nodeService = mock(NodeService.class);
        RunnerSession target = mock(RunnerSession.class);
        when(target.send(any())).thenReturn(true);
        when(nodeService.getCurrentTarget(RUNNER_ID))
                .thenReturn(Optional.of(new NodeService.SessionTarget(target, 7L)));
        when(nodeService.withRunnerLock(org.mockito.ArgumentMatchers.eq(RUNNER_ID), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        QueryManager queryManager = new QueryManager(nodeService);

        ContainerStatusRequest req = ContainerStatusRequest.newBuilder().setServiceId("1001").build();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ContainerStatusResponse>> running =
                    pool.submit(() -> queryManager.queryContainerStatus(RUNNER_ID, req, 5000));
            Thread.sleep(200);

            queryManager.failPendingForNode(RUNNER_ID, 0L, "legacy-signature");
            Thread.sleep(100);
            assertFalse(running.isDone(), "身份未知时不得失败该查询（交由查询超时兜底）");

            // 正常回执仍能完成该查询，说明等待状态未被破坏
            RunnerSession session = new RunnerSession(RUNNER_ID, new EmbeddedChannel(), "host", "127.0.0.1", "0.6.2");
            queryManager.onContainerStatus(session, ContainerStatusResponse.newBuilder().setRunning(true).build());
            assertTrue(running.get(2, TimeUnit.SECONDS).isPresent());
        } finally {
            pool.shutdownNow();
        }
    }
}
