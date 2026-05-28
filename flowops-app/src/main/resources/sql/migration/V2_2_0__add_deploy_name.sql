-- deploy_service 新增 deploy_name + remark 字段
ALTER TABLE deploy_service ADD COLUMN deploy_name VARCHAR(63) NOT NULL AFTER name;
ALTER TABLE deploy_service ADD UNIQUE KEY uk_deploy_name (deploy_name);
ALTER TABLE deploy_service ADD COLUMN remark VARCHAR(500) DEFAULT NULL COMMENT '服务备注' AFTER deploy_name;

-- 已有服务的 deploy_name 回填
UPDATE deploy_service SET deploy_name = CONCAT('service-', id) WHERE deploy_name = '';
