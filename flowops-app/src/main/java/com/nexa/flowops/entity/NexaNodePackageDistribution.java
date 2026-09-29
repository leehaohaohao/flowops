package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布包分发记录（阶段 2 P3）：把已校验的通用包通过 SFTP 送到目标机
 * {@code /opt/flowops/runner/packages/<sha256>.tar.gz}。
 *
 * <p>状态机：{@code PENDING → UPLOADING → VERIFYING → SUCCEEDED/FAILED}（终态）。
 * {@code already_present} 表示目标机已有同摘要正式包且远端摘要一致，视为成功且不传输。
 *
 * <p>失败时保留目标机既有正式包并清理本次 {@code <sha256>.part}；本表只保存固定错误码与固定中文说明，
 * 不含 token、私钥、远端命令输出或异常栈。
 */
@Data
@TableName("nexa_node_package_distribution")
public class NexaNodePackageDistribution {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 目标节点，对应 nexa_node.runner_id */
    private String runnerId;
    /** 包摘要 */
    private String packageSha256;
    /**
     * 绑定本次分发的 SSH 配置版本（{@code nexa_node_ssh_target.config_version}，建立记录时的快照）。
     *
     * <p>{@code null} 表示未绑定（迁移 {@code V3_1_9} 之前的历史记录）：后台任务一律按失效处理，
     * 落 {@code SSH_CONFIG_CHANGED}，不建立远端会话。
     */
    private Long sshConfigVersion;
    /** PENDING/UPLOADING/VERIFYING/SUCCEEDED/FAILED */
    private String status;
    /** 目标机已有同摘要包而跳过传输 */
    private Boolean alreadyPresent;
    /** 失败固定错误码（阶段 1 连接码，或分发特有的 REMOTE_ / UPLOAD_ 前缀） */
    private String errorCode;
    /** 失败固定中文说明 */
    private String errorMessage;
    /** 远端正式包路径（固定、非敏感） */
    private String remotePath;
    /** 包字节数快照 */
    private Long sizeBytes;
    /** 触发分发的超级管理员 */
    private String operator;
    /** 开始执行时间 */
    private LocalDateTime startedAt;
    /** 终态时间 */
    private LocalDateTime finishedAt;
    /** 终态耗时（毫秒） */
    private Long durationMs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
