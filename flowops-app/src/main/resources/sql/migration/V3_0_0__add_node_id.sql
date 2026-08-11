-- 3.0.0: 分布式部署 - 节点分配字段
-- deploy_service 增加目标执行节点；deploy_record 记录实际执行节点
ALTER TABLE deploy_service ADD COLUMN node_id VARCHAR(63) DEFAULT NULL COMMENT '目标执行节点 runnerId；NULL=本机，auto=自动调度' AFTER status;
ALTER TABLE deploy_record ADD COLUMN node_id VARCHAR(63) DEFAULT NULL COMMENT '实际执行节点 runnerId；NULL=本机执行' AFTER remark;
