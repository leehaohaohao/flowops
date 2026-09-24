package com.nexa.flowops.service.node;

import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.protocol.Task.TaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 远程任务回执管理：登记下发任务，收到子节点回执后落库 deploy_record + 更新服务状态，
 * 并对节点掉线 / 超时任务标记失败。
 *
 * <p>重连语义（见 docs/2026-09-23-runner-connection-recovery-plan.md）：
 * <ul>
 *   <li>连接恢复只恢复“接收新任务”的能力，<b>不会重放</b>断线前的任务——
 *       避免重复执行部署动作；未回执的任务由 {@link #sweepTimeouts()} 超时清扫标记失败</li>
 *   <li>只有与任务下发节点一致的会话回执才会被采纳（L2 身份校验，见
 *       {@link #onTaskResult(TaskResponse, String)}）</li>
 *   <li>节点掉线时由 {@code FlowOpsMasterListener.onDisconnect} 触发失败化；
 *       若该节点已重连成功（存在健康新会话），迟到断开事件会被跳过，不会误伤新会话任务</li>
 * </ul>
 */
@Component
public class RemoteTaskManager {

    private static final Logger log = LoggerFactory.getLogger(RemoteTaskManager.class);

    private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L; // 10 分钟

    private final DeployRecordMapper recordMapper;
    private final DeployServiceMapper serviceMapper;

    private final ConcurrentMap<String, PendingTask> pendingTasks = new ConcurrentHashMap<>();

    public RemoteTaskManager(DeployRecordMapper recordMapper, DeployServiceMapper serviceMapper) {
        this.recordMapper = recordMapper;
        this.serviceMapper = serviceMapper;
    }

    public static long defaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    public void register(PendingTask task) {
        pendingTasks.put(task.taskId(), task);
    }

    /**
     * 子节点回执回调入口（由 FlowOpsMasterListener 调用）。
     * sessionRunnerId 为回执消息所在会话的 runnerId，用于 L2 身份校验：
     * 仅接受与任务下发节点一致的会话回执，伪造回执直接丢弃（pending 任务留待超时清扫标记失败）。
     */
    public void onTaskResult(TaskResponse resp, String sessionRunnerId) {
        PendingTask task = pendingTasks.get(resp.getTaskId());
        if (task == null) {
            log.warn("[Master] 收到未知任务回执: taskId={}, runnerId={}, success={}",
                    resp.getTaskId(), resp.getRunnerId(), resp.getSuccess());
            return;
        }
        // L2：会话身份必须与任务下发节点一致
        if (sessionRunnerId == null || !sessionRunnerId.equals(task.nodeId())) {
            log.warn("[Master] 丢弃伪造任务回执: taskId={}, 会话runnerId={}, 任务下发节点={}",
                    resp.getTaskId(), sessionRunnerId, task.nodeId());
            return;
        }
        pendingTasks.remove(resp.getTaskId());

        DeployService service = serviceMapper.selectById(task.serviceId());
        DeployRecord record = recordMapper.selectById(task.recordId());

        StringBuilder content = new StringBuilder();
        content.append("===== 子节点回执 =====\n");
        content.append("runnerId: ").append(resp.getRunnerId()).append("\n");
        content.append("exitCode: ").append(resp.getExitCode()).append("\n");
        if (resp.getOutput() != null && !resp.getOutput().isEmpty()) {
            content.append("----- output -----\n").append(resp.getOutput()).append("\n");
        }
        if (resp.getError() != null && !resp.getError().isEmpty()) {
            content.append("----- error -----\n").append(resp.getError()).append("\n");
        }
        appendLog(task.logPath(), content.toString());

        if (record != null) {
            record.setStatus(resp.getSuccess() ? "success" : "failed");
            record.setRemark(resp.getSuccess()
                    ? "子节点执行成功: " + resp.getRunnerId()
                    : "子节点执行失败: " + resp.getRunnerId() + "，exitCode=" + resp.getExitCode());
            recordMapper.updateById(record);
        }

        if (service != null) {
            service.setStatus(resp.getSuccess() ? task.successStatus() : "stopped");
            serviceMapper.updateById(service);
        }

        if (resp.getSuccess()) {
            log.info("[Master] 任务执行成功: taskId={}, serviceId={}, nodeId={}",
                    task.taskId(), task.serviceId(), task.nodeId());
        } else {
            log.warn("[Master] 任务执行失败: taskId={}, serviceId={}, nodeId={}, exitCode={}",
                    task.taskId(), task.serviceId(), task.nodeId(), resp.getExitCode());
        }
    }

    /**
     * 节点掉线时失败化待处理任务。
     *
     * <p><b>按会话代次归因</b>：只失败化“下发时所针对会话代次 == 事件所属代次”的任务。
     * 事件归属由 {@code FlowOpsMasterListener} 按协议会话注册表判定；即使协议在
     * “读注册表 → 清理”之间完成新会话注册，接管者刚下发的任务（代次不同）也不会被误失败化。
     *
     * @param sessionGeneration 事件所属会话代次；&lt;= 0 表示身份未知（旧签名/未绑定），
     *                          此时不失败化任何任务，交由 {@link #sweepTimeouts()} 兜底
     */
    public void failTasksForNode(String nodeId, long sessionGeneration, String reason) {
        if (nodeId == null) {
            return;
        }
        if (sessionGeneration <= 0) {
            log.warn("[Master] 断开事件缺少会话代次，跳过任务失败化（交由超时清扫兜底）: nodeId={}, reason={}",
                    nodeId, reason);
            return;
        }
        int failed = 0;
        for (Map.Entry<String, PendingTask> entry : pendingTasks.entrySet()) {
            PendingTask task = entry.getValue();
            if (nodeId.equals(task.nodeId())
                    && task.sessionGeneration() == sessionGeneration
                    && pendingTasks.remove(entry.getKey(), task)) {
                failTask(task, "节点掉线: " + reason);
                failed++;
            }
        }
        if (failed > 0) {
            log.warn("[Master] 节点 {} 掉线，已标记 {} 个 pending 任务为失败 (generation={})",
                    nodeId, failed, sessionGeneration);
        }
    }

    /**
     * 定时清扫超时未回执的任务
     */
    @Scheduled(fixedDelay = 15000, initialDelay = 30000)
    public void sweepTimeouts() {
        for (Map.Entry<String, PendingTask> entry : pendingTasks.entrySet()) {
            PendingTask task = entry.getValue();
            if (task.expired() && pendingTasks.remove(entry.getKey(), task)) {
                failTask(task, "任务超时（超过 " + (task.timeoutMs() / 60000) + " 分钟未收到回执）");
            }
        }
    }

    private void failTask(PendingTask task, String reason) {
        DeployRecord record = recordMapper.selectById(task.recordId());
        if (record != null) {
            record.setStatus("failed");
            record.setRemark(reason);
            recordMapper.updateById(record);
        }
        DeployService service = serviceMapper.selectById(task.serviceId());
        if (service != null) {
            service.setStatus("stopped");
            serviceMapper.updateById(service);
        }
        appendLog(task.logPath(), "===== 任务失败 =====\n" + reason + "\n");
        log.warn("[Master] 任务标记失败: taskId={}, serviceId={}, nodeId={}, reason={}",
                task.taskId(), task.serviceId(), task.nodeId(), reason);
    }

    private void appendLog(String logPath, String content) {
        try {
            File logFile = new File(logPath);
            Files.createDirectories(logFile.getParentFile().toPath());
            Files.writeString(logFile.toPath(), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("写入部署日志失败: logPath={}", logPath, e);
        }
    }
}
