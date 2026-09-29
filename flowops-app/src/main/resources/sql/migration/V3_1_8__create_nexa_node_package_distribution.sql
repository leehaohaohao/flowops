-- 3.1.8: 发布包分发记录（阶段 2 P3：主节点 → 目标机 /opt/flowops/runner/packages/）
-- 分发只投递六成员通用包：不解压、不执行包内内容、不写节点凭据、不启动子节点。
-- 状态机：PENDING → UPLOADING → VERIFYING → SUCCEEDED / FAILED（终态）；already_present=1 表示
-- 目标机已有同摘要正式包、远端摘要一致，直接判定成功且不传输。
-- 失败时保留既有正式包、清理本次 <sha256>.part；本表只记录固定错误码与固定中文说明，
-- 不保存 token、私钥、远端命令输出或异常栈。
CREATE TABLE IF NOT EXISTS nexa_node_package_distribution (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    runner_id       VARCHAR(63)  NOT NULL COMMENT '目标节点，对应 nexa_node.runner_id',
    package_sha256  CHAR(64)     NOT NULL COMMENT '包摘要',
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/UPLOADING/VERIFYING/SUCCEEDED/FAILED',
    already_present TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '目标机已有同摘要包而跳过传输',
    error_code      VARCHAR(48)  DEFAULT NULL COMMENT '失败固定错误码：阶段 1 连接码或 REMOTE_*/UPLOAD_*',
    error_message   VARCHAR(255) DEFAULT NULL COMMENT '失败固定中文说明',
    remote_path     VARCHAR(255) DEFAULT NULL COMMENT '远端正式包路径（固定、非敏感）',
    size_bytes      BIGINT       DEFAULT NULL COMMENT '包字节数快照',
    operator        VARCHAR(64)  DEFAULT NULL COMMENT '触发分发的超级管理员',
    started_at      DATETIME     DEFAULT NULL COMMENT '开始执行时间',
    finished_at     DATETIME     DEFAULT NULL COMMENT '终态时间',
    duration_ms     BIGINT       DEFAULT NULL COMMENT '终态耗时（毫秒）',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_pkg_dist_node (runner_id, package_sha256, id),
    KEY idx_pkg_dist_status (status),
    CONSTRAINT fk_pkg_dist_node FOREIGN KEY (runner_id) REFERENCES nexa_node (runner_id) ON DELETE CASCADE
);
