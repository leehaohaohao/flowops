package com.nexa.flowops.service.node;

/**
 * 已下发到子节点、等待回执的任务。
 * taskId 与 TaskRequest.task_id 对应，用于关联子节点回执。
 *
 * @param sessionGeneration 下发时所针对的会话代次标签（取自协议会话注册表）；
 *                          掉线清理只失败化同一代次的任务，避免误伤接管者刚下发的工作
 */
public record PendingTask(
        String taskId,
        Long serviceId,
        String nodeId,
        String action,
        String logPath,
        Long recordId,
        String successStatus,
        long sessionGeneration,
        long createTime,
        long timeoutMs) {

    public boolean expired() {
        return System.currentTimeMillis() - createTime > timeoutMs;
    }
}
