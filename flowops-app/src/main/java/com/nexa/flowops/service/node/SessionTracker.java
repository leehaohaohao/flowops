package com.nexa.flowops.service.node;

import com.nexa.protocol.master.RunnerSession;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 会话代次与按节点临界区。
 *
 * <p>解决的问题（见 docs/2026-09-23-runner-connection-recovery-plan.md D.2）：
 * 协议 v0.6.1/v0.6.2 保证「条件移除当前会话成功才通知断开一次」，但从“旧会话被移除”到“断开回调执行”
 * 之间，同 runnerId 的新连接仍可能完成注册并开始接收任务。若后端只按 runnerId 清理，
 * 旧会话的迟到回调会失败化新会话的任务/查询、并把已恢复的节点标成离线。
 *
 * <p><b>重要：代次仅作诊断标签，不作为归属判定依据。</b>
 * 后端 {@code onRegister} 回调发生在协议把会话写入注册表<b>之前</b>（RegisterHandler 先回调认证、
 * 再 register），因此两个同 ID 连接并发注册时，代次的分配顺序可能与最终生效的会话顺序相反
 * （注册表里最后的 register 调用者才是当前会话）。若拿“最后一次分配的代次”当作“当前代次”比较，
 * 就会把真正在线的会话误判为旧会话而跳过清理。
 *
 * <p>因此：<b>归属判定一律以协议会话注册表为准</b>——断开事件到达时，事件会话已被移除，
 * 注册表中若仍存在会话，它必然是接管者。代次只用于日志诊断与后续可能的按会话归因。
 * 按节点临界区则用于序列化本类自己的注册/断开处理（DB 状态写入顺序、避免重复清理）。
 */
@Component
public class SessionTracker {

    /** 会话代次绑定在连接 channel 上；未绑定（身份未知）返回 0 */
    private static final AttributeKey<Long> SESSION_GENERATION_ATTR =
            AttributeKey.valueOf("flowops.sessionGeneration");

    private final AtomicLong generationSequence = new AtomicLong();
    /** runnerId -> 按节点临界区锁（数量与接入节点数同阶） */
    private final ConcurrentMap<String, Object> runnerLocks = new ConcurrentHashMap<>();

    /**
     * 注册成功：为该连接分配一个唯一的代次标签（仅用于诊断，其大小顺序不代表会话先后）。
     */
    public long beginSession() {
        return generationSequence.incrementAndGet();
    }

    /** 把代次标签绑定到会话连接，供断开回调日志诊断 */
    public void bindGeneration(RunnerSession session, long generation) {
        if (session != null && session.getChannel() != null) {
            session.getChannel().attr(SESSION_GENERATION_ATTR).set(generation);
        }
    }

    /** 读取会话代次标签；未绑定（身份未知）返回 0 */
    public long generationOf(RunnerSession session) {
        if (session == null || session.getChannel() == null) {
            return 0L;
        }
        Long generation = session.getChannel().attr(SESSION_GENERATION_ATTR).get();
        return generation == null ? 0L : generation;
    }

    /**
     * 在指定节点的临界区内执行并返回结果（本类的注册/断开处理互斥）
     */
    public <T> T withRunnerLock(String runnerId, Supplier<T> action) {
        Object lock = runnerLocks.computeIfAbsent(runnerId, key -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }

    /**
     * 在指定节点的临界区内执行（无返回值）
     */
    public void runWithRunnerLock(String runnerId, Runnable action) {
        withRunnerLock(runnerId, () -> {
            action.run();
            return null;
        });
    }
}
