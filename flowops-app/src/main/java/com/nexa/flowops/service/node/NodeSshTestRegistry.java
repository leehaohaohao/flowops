package com.nexa.flowops.service.node;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexa.flowops.entity.NexaNodeSshTarget;
import com.nexa.flowops.mapper.NexaNodeSshTargetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * SSH 连接测试的登记与写回（R1：解决"测试期间改配置"和"两次测试后发先至"两个竞态）。
 *
 * <p>规则：
 * <ol>
 *   <li>{@link #begin(String)} 在一个数据库事务内读取<b>不可变设置快照</b>（含当时的 {@code config_version}）
 *       并原子递增 {@code latest_test_seq} 登记本次测试序号。</li>
 *   <li>{@link #complete} 只在 {@code config_version} 与 {@code latest_test_seq} 都与快照一致时写回
 *       {@code last_*}；否则返回 {@code false}，表示该结果对当前设置无效（调用方返回 {@code TEST_OBSOLETE}，
 *       且不把结果写成当前设置的 lastTest）。</li>
 *   <li>{@code PUT} 覆盖设置时递增 {@code config_version} 并清空 {@code latest_test_seq} 与旧结果，
 *       使所有在途测试自动过期。</li>
 * </ol>
 *
 * <p>两个条件都在同一条 UPDATE 的 WHERE 中判定，由数据库保证原子性；测试过程本身不占用事务。
 */
@Service
@RequiredArgsConstructor
public class NodeSshTestRegistry {

    private final NexaNodeSshTargetMapper targetMapper;

    /** 一次测试的不可变设置快照：探测只读这份快照，写回只认这份快照的版本与序号。 */
    public record TestSnapshot(String host, int port, String username, String keyAlias, String hostKeySha256,
                              String hostKeyAlgorithm, long configVersion, long testSeq) {
    }

    /**
     * 登记一次测试并返回设置快照。
     *
     * <p>事务内完成"读快照 + 递增序号"，并与 {@code PUT} 的写入互斥（同一行行锁），
     * 因此快照的版本与序号是自洽的。
     */
    @Transactional
    public TestSnapshot begin(String runnerId) {
        NexaNodeSshTarget target = targetMapper.selectById(runnerId);
        if (target == null) {
            throw SshTargetException.notConfigured();
        }
        targetMapper.update(null, Wrappers.<NexaNodeSshTarget>lambdaUpdate()
                .eq(NexaNodeSshTarget::getRunnerId, runnerId)
                .setSql("latest_test_seq = latest_test_seq + 1"));

        // UPDATE 会清空 MyBatis 本地缓存，这里读回的是本事务内递增后的序号
        NexaNodeSshTarget registered = targetMapper.selectById(runnerId);
        long testSeq = registered.getLatestTestSeq() == null ? 1L : registered.getLatestTestSeq();
        long configVersion = registered.getConfigVersion() == null ? 0L : registered.getConfigVersion();

        return new TestSnapshot(registered.getHost(),
                registered.getPort() == null ? 22 : registered.getPort(),
                registered.getUsername(), registered.getKeyAlias(), registered.getHostKeySha256(),
                registered.getHostKeyAlgorithm(),
                configVersion, testSeq);
    }

    /**
     * 在版本与测试序号都匹配时写回结果。
     *
     * @return true=已写为当前设置的 lastTest；false=结果已过期，未写入
     */
    public boolean complete(String runnerId, TestSnapshot snapshot, SshTestResultCode code,
                            long durationMs, LocalDateTime testedAt) {
        int updated = targetMapper.update(null, Wrappers.<NexaNodeSshTarget>lambdaUpdate()
                .eq(NexaNodeSshTarget::getRunnerId, runnerId)
                .eq(NexaNodeSshTarget::getConfigVersion, snapshot.configVersion())
                .eq(NexaNodeSshTarget::getLatestTestSeq, snapshot.testSeq())
                .set(NexaNodeSshTarget::getLastResultCode, code.name())
                .set(NexaNodeSshTarget::getLastResultMessage, code.message())
                .set(NexaNodeSshTarget::getLastTestedAt, testedAt)
                .set(NexaNodeSshTarget::getLastDurationMs, durationMs)
                .set(NexaNodeSshTarget::getUpdateTime, LocalDateTime.now()));
        return updated > 0;
    }
}
