package com.nexa.flowops.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布包分发记录响应（阶段 2 P3，契约 {@code docs/frontend-api/node-package-api.md} §5.1）。
 *
 * <p>失败原因只以固定错误码 + 固定中文说明返回；{@code remotePath} 是固定的非敏感目标路径。
 */
@Data
public class PackageDistributionVO {

    private Long id;
    private String runnerId;
    private String packageSha256;
    /**
     * 本次分发绑定的、已通过连接测试的 SSH 配置版本（阶段 1 {@code nexa_node_ssh_target.config_version}）。
     *
     * <p>为 {@code null} 时表示未绑定（旧记录），该分发必然失败为 {@code SSH_CONFIG_CHANGED}；
     * 展示它便于判断"SSH 设置是否已变更、需重新测试连接"。
     */
    private Long sshConfigVersion;
    private String version;
    private String fileName;
    private Long sizeBytes;
    /** PENDING / UPLOADING / VERIFYING / SUCCEEDED / FAILED */
    private String status;
    /** 目标机已有同摘要包而跳过传输即成功 */
    private Boolean alreadyPresent;
    /** 失败固定错误码（阶段 1 连接码，或分发特有的 REMOTE_ / UPLOAD_ 前缀）；成功为 null */
    private String errorCode;
    /** 失败固定中文说明，可直接展示 */
    private String errorMessage;
    /** 远端正式包路径（固定、非敏感） */
    private String remotePath;
    private String operator;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
}
