-- 3.1.9: 分发记录绑定"已验证的 SSH 配置版本"（阶段 2 审阅 R1 / 行 B1）
-- 背景：分发请求在排队（PENDING）与后台执行之间存在时间窗，期间管理员可修改 SSH 设置。
-- 绑定后，后台任务在建立远端会话之前必须证明"当前 config_version == 本列记录的版本"且
-- "最近一次测试仍是 CONNECTED"，否则以固定失败码终止且不写远端。
-- 语义：
--   * 新建记录时必须写入该列（取自当时已通过连接测试的 nexa_node_ssh_target.config_version）；
--   * NULL 表示未绑定（本迁移之前的历史记录或绑定前创建），分发时一律视为失效 → SSH_CONFIG_CHANGED；
--   * 本列是快照，不随 SSH 设置变化；SSH 设置每次 PUT 覆盖时 config_version +1，从而使在途任务自动失效。
ALTER TABLE nexa_node_package_distribution
    ADD COLUMN ssh_config_version BIGINT DEFAULT NULL COMMENT '绑定分发的 SSH 配置版本（nexa_node_ssh_target.config_version）；NULL=未绑定，分发时按失效处理' AFTER package_sha256;
