-- 2.0.0: deploy_service 新增 extra_ports 字段，支持多端口映射
ALTER TABLE deploy_service ADD COLUMN extra_ports VARCHAR(500) DEFAULT NULL COMMENT '额外端口映射 JSON: [{"hostPort":9090,"containerPort":9090}]';
