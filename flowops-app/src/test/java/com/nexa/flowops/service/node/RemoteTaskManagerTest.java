package com.nexa.flowops.service.node;

import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 待处理任务的会话归因测试（D.2）：
 * 掉线清理只失败化“下发时所针对会话代次 == 事件所属代次”的任务，接管者刚下发的任务不受影响。
 */
class RemoteTaskManagerTest {

    private static final String RUNNER_ID = "runner-1";
    private static final long SERVICE_ID = 1001L;

    private DeployRecordMapper recordMapper;
    private RemoteTaskManager taskManager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        recordMapper = mock(DeployRecordMapper.class);
        DeployServiceMapper serviceMapper = mock(DeployServiceMapper.class);
        taskManager = new RemoteTaskManager(recordMapper, serviceMapper);
    }

    @Test
    void failTasksForNode_onlyFailsTasksOfThatSessionGeneration() {
        DeployRecord oldSessionRecord = record(101L);
        DeployRecord newSessionRecord = record(102L);
        when(recordMapper.selectById(101L)).thenReturn(oldSessionRecord);
        when(recordMapper.selectById(102L)).thenReturn(newSessionRecord);

        taskManager.register(pendingTask("task-old", 101L, 1L));
        taskManager.register(pendingTask("task-new", 102L, 2L));

        // 旧会话（代次 1）掉线
        taskManager.failTasksForNode(RUNNER_ID, 1L, "connection_lost");

        assertEquals("failed", oldSessionRecord.getStatus(), "同代次任务应被失败化");
        assertEquals("节点掉线: connection_lost", oldSessionRecord.getRemark());
        assertNull(newSessionRecord.getStatus(), "接管者（新代次）的任务不得被失败化");
        verify(recordMapper, times(1)).updateById(any());
    }

    @Test
    void failTasksForNode_ignoresTasksOfOtherNode() {
        DeployRecord record = record(201L);
        when(recordMapper.selectById(201L)).thenReturn(record);
        taskManager.register(new PendingTask("task-other-node", SERVICE_ID, "runner-2", "START",
                tempDir.resolve("deploy-other.log").toString(), 201L, "running", 1L,
                System.currentTimeMillis(), RemoteTaskManager.defaultTimeoutMs()));

        taskManager.failTasksForNode(RUNNER_ID, 1L, "connection_lost");

        assertNull(record.getStatus());
        verify(recordMapper, never()).updateById(any());
    }

    @Test
    void failTasksForNode_withUnknownGeneration_failsNothing() {
        DeployRecord record = record(301L);
        when(recordMapper.selectById(301L)).thenReturn(record);
        taskManager.register(pendingTask("task-1", 301L, 1L));

        // 身份未知（旧签名/未绑定代次）：保守处理，交由超时清扫兜底
        taskManager.failTasksForNode(RUNNER_ID, 0L, "connection_lost");

        assertNull(record.getStatus());
        verify(recordMapper, never()).updateById(any());
    }

    @Test
    void failTasksForNode_isIdempotentPerGeneration() {
        DeployRecord record = record(401L);
        when(recordMapper.selectById(401L)).thenReturn(record);
        taskManager.register(pendingTask("task-1", 401L, 5L));

        taskManager.failTasksForNode(RUNNER_ID, 5L, "heartbeat_timeout");
        taskManager.failTasksForNode(RUNNER_ID, 5L, "heartbeat_timeout");

        verify(recordMapper, times(1)).updateById(any()); // 第二次已无该代次任务
    }

    private PendingTask pendingTask(String taskId, Long recordId, long sessionGeneration) {
        return new PendingTask(taskId, SERVICE_ID, RUNNER_ID, "START",
                tempDir.resolve(taskId + ".log").toString(), recordId, "running", sessionGeneration,
                System.currentTimeMillis(), RemoteTaskManager.defaultTimeoutMs());
    }

    private DeployRecord record(Long id) {
        DeployRecord record = new DeployRecord();
        record.setId(id);
        record.setServiceId(SERVICE_ID);
        return record;
    }
}
