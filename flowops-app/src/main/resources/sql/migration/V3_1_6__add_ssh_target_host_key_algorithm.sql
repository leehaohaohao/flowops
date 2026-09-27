-- 3.1.6: SSH 目标显式记录主机密钥算法（H1.2/H1.3）
-- 目标机可能同时启用多把主机密钥（Ed25519 / ECDSA / RSA），"主机密钥指纹"只有与算法配对后才唯一：
--   host_key_algorithm 与 host_key_sha256 指向同一把经管理员核对过的目标机主机公钥。
-- 允许为 NULL 仅用于兼容已有记录：旧记录在补齐算法前不允许继续测试（返回 HOST_KEY_ALGORITHM_REQUIRED），
-- 不做任何自动假定（尤其不得把日志里的观察值或 ED25519 当作默认）。
ALTER TABLE nexa_node_ssh_target
    ADD COLUMN host_key_algorithm VARCHAR(16) DEFAULT NULL COMMENT '主机密钥算法：ED25519/ECDSA/RSA；NULL=旧记录待补齐' AFTER host_key_sha256;
