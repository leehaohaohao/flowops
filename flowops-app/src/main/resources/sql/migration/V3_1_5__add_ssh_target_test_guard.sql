-- 3.1.5: SSH 测试结果的并发保护（R1）
-- config_version：每次 PUT /ssh 覆盖设置时 +1；测试结束写回 last_* 时必须与测试开始时的快照版本一致，
--                 否则说明测试期间设置已被改动，该结果对当前设置无效。
-- latest_test_seq：测试开始时原子 +1 登记本次测试；只有仍是最新序号的测试才能写回 last_*，
--                 避免"先发起、后完成"的旧测试覆盖较新测试的结果。
ALTER TABLE nexa_node_ssh_target
    ADD COLUMN config_version  BIGINT NOT NULL DEFAULT 0 COMMENT 'SSH 设置版本，PUT 覆盖设置时递增' AFTER host_key_sha256,
    ADD COLUMN latest_test_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最新一次测试的序号，测试开始时递增登记' AFTER last_duration_ms;
