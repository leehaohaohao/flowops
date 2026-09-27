-- 3.1.4: 子节点宿主机 SSH 目标（阶段 1：主节点到子节点宿主机的 SSH 连接验证）
-- 身份沿用 nexa_node.runner_id；本表只保存私钥别名，私钥文件由管理员放在宿主机
-- /data/flowops/ssh-keys/<key_alias>（部署脚本已把 /data/flowops 挂载进容器），数据库不保存私钥内容。
-- 与 Nexa Protocol 的子节点连接是两条独立链路：本表不影响 nexa_node.token 与在线状态。
CREATE TABLE IF NOT EXISTS nexa_node_ssh_target (
    runner_id           VARCHAR(63)  NOT NULL COMMENT '节点ID，对应 nexa_node.runner_id',
    host                VARCHAR(253) NOT NULL COMMENT '宿主机地址，不含端口与 scheme',
    port                INT          NOT NULL DEFAULT 22 COMMENT 'SSH 端口',
    username            VARCHAR(64)  NOT NULL COMMENT 'SSH 用户名',
    key_alias           VARCHAR(128) NOT NULL COMMENT '私钥别名；容器内实际路径为 <flowops.ssh.key-dir>/<key_alias>',
    host_key_sha256     VARCHAR(80)  NOT NULL COMMENT '远端主机密钥指纹，归一化为 SHA256:<43字符无填充base64>',
    last_result_code    VARCHAR(32)  DEFAULT NULL COMMENT '最近一次连接测试结果码',
    last_result_message VARCHAR(255) DEFAULT NULL COMMENT '最近一次连接测试结果说明',
    last_tested_at      DATETIME     DEFAULT NULL COMMENT '最近一次连接测试时间',
    last_duration_ms    BIGINT       DEFAULT NULL COMMENT '最近一次连接测试耗时（毫秒）',
    create_time         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (runner_id),
    CONSTRAINT fk_ssh_target_node FOREIGN KEY (runner_id) REFERENCES nexa_node (runner_id) ON DELETE CASCADE
);
