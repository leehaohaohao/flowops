package com.nexa.flowops.service.node;

/**
 * 已下发到子节点、等待回执的任务。
 * taskId 与 TaskRequest.task_id 对应，用于关联子节点回执。
 */
public record PendingTask(
        String taskId,
        Long serviceId,
        String nodeId,
        String action,
        String logPath,
        Long recordId,
        String successStatus,
        long createTime,
        long timeoutMs) {

    public boolean expired() {
        return System.currentTimeMillis() - createTime > timeoutMs;
    }
}
