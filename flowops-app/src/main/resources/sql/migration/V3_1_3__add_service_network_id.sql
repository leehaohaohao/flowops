-- 3.1.3: 服务接入共享网络
-- deploy_service.network_id 为空表示不加入共享网络（旧服务保持 NULL，Compose 输出与调度行为不变）
-- 引用约束：只允许引用已登记网络；仍被服务引用的网络不允许删除登记行
ALTER TABLE deploy_service ADD COLUMN network_id BIGINT DEFAULT NULL COMMENT '共享网络ID；NULL=不加入共享网络' AFTER node_id;
CREATE INDEX idx_service_network ON deploy_service (network_id);
ALTER TABLE deploy_service ADD CONSTRAINT fk_service_network FOREIGN KEY (network_id) REFERENCES docker_network (id);
