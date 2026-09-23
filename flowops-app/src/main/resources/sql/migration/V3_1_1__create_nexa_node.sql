-- 3.1.1: 子节点注册表（L1 注册认证）
-- token 为注册令牌的 sha256(hex)（原始令牌仅节点侧与录入方知晓）
CREATE TABLE IF NOT EXISTS nexa_node (
    runner_id      VARCHAR(63) PRIMARY KEY,
    node_name      VARCHAR(255),
    token          CHAR(64) NOT NULL COMMENT '注册令牌 sha256(hex)',
    status         VARCHAR(16) DEFAULT 'offline' COMMENT 'online / offline',
    last_heartbeat DATETIME,
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 录入方式（推荐管理 API，仅超管；token 明文传入服务端自动 sha256）：
--   GET    /api/nodes/registry            查看全部已登记节点
--   POST   /api/nodes/registry            新增登记 {"runnerId":"...","nodeName":"...","token":"..."}
--   PUT    /api/nodes/registry/{runnerId} 更新（nodeName/token 可选）
--   DELETE /api/nodes/registry/{runnerId} 删除登记
-- 或手动 SQL：INSERT INTO nexa_node (runner_id, node_name, token) VALUES ('runner-1', '节点1', SHA2('my-token', 256));
