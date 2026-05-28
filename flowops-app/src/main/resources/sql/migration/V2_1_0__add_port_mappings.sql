-- 2.1.0: deploy_service 新增 port_mappings 字段，统一端口映射 JSON
ALTER TABLE deploy_service ADD COLUMN port_mappings TEXT DEFAULT NULL COMMENT '端口映射 JSON: List<PortMapping>';
