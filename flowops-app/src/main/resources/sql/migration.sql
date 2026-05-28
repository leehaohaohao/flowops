-- FlowOps 数据库迁移脚本
-- 按顺序执行各阶段 SQL

USE flowops;

-- ============================================
-- 阶段 1: 基础表修改
-- ============================================

-- sys_user 新增超级管理员标识
ALTER TABLE sys_user ADD COLUMN is_super_admin TINYINT(1) NOT NULL DEFAULT 0 AFTER role;

-- deploy_service 新增项目归属
ALTER TABLE deploy_service ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1 AFTER id;


-- ============================================
-- 阶段 2: 权限表创建
-- ============================================

-- 项目（合并了原 project_group 和 project 的概念）
CREATE TABLE IF NOT EXISTS project (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_default  TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为默认项目（不可删除）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 项目成员
CREATE TABLE IF NOT EXISTS group_member (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id        BIGINT NOT NULL,
    user_id           BIGINT NOT NULL,
    role_id           BIGINT NOT NULL,
    extra_permissions VARCHAR(500) DEFAULT NULL COMMENT '额外权限码，逗号分隔',
    create_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_user (project_id, user_id),
    INDEX idx_user_id (user_id)
);

-- 权限定义
CREATE TABLE IF NOT EXISTS perm_definition (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(200)
);

-- 角色
CREATE TABLE IF NOT EXISTS perm_role (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    is_preset   TINYINT(1) NOT NULL DEFAULT 0,
    group_id    BIGINT DEFAULT NULL,
    description VARCHAR(200),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name_group (name, group_id)
);

-- 角色-权限映射
CREATE TABLE IF NOT EXISTS role_permission (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id   BIGINT NOT NULL,
    perm_code VARCHAR(50) NOT NULL,
    UNIQUE KEY uk_role_perm (role_id, perm_code)
);

-- 跨项目授权
CREATE TABLE IF NOT EXISTS project_access (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    perm_code  VARCHAR(50) NOT NULL,
    UNIQUE KEY uk_user_project_perm (user_id, project_id, perm_code),
    INDEX idx_user_id (user_id)
);


-- ============================================
-- 阶段 3: 种子数据
-- ============================================

-- 默认项目
INSERT INTO project (id, name, description, is_default) VALUES (1, '默认项目', '系统默认项目', 1);

-- 权限定义
INSERT INTO perm_definition (code, description) VALUES
('VIEW', '查看服务列表和状态'),
('DEPLOY', '部署/启动服务'),
('START', '启动已停止的服务'),
('STOP', '停止运行中的服务'),
('UPLOAD', '上传 JAR/dist 文件'),
('EDIT_CONFIG', '编辑服务配置'),
('DELETE', '删除服务');

-- 预设角色
INSERT INTO perm_role (name, is_preset, description) VALUES
('viewer',     1, '只读权限'),
('operator',   1, '运维操作权限'),
('editor',     1, '编辑权限'),
('admin',      1, '项目管理员，全部权限'),
('supervisor', 1, '项目主管，可管理成员和项目');

-- 角色-权限映射
INSERT INTO role_permission (role_id, perm_code) VALUES (1, 'VIEW');
INSERT INTO role_permission (role_id, perm_code) VALUES (2, 'VIEW'), (2, 'DEPLOY'), (2, 'START'), (2, 'STOP');
INSERT INTO role_permission (role_id, perm_code) VALUES (3, 'VIEW'), (3, 'DEPLOY'), (3, 'START'), (3, 'STOP'), (3, 'UPLOAD'), (3, 'EDIT_CONFIG');
INSERT INTO role_permission (role_id, perm_code) VALUES (4, 'VIEW'), (4, 'DEPLOY'), (4, 'START'), (4, 'STOP'), (4, 'UPLOAD'), (4, 'EDIT_CONFIG'), (4, 'DELETE');
INSERT INTO role_permission (role_id, perm_code) VALUES (5, 'VIEW'), (5, 'DEPLOY'), (5, 'START'), (5, 'STOP'), (5, 'UPLOAD'), (5, 'EDIT_CONFIG'), (5, 'DELETE'), (5, 'MANAGE_MEMBERS'), (5, 'MANAGE_PROJECTS');


-- ============================================
-- 阶段 4: 数据迁移
-- ============================================

-- 现有 admin 用户标记为超级管理员
UPDATE sys_user SET is_super_admin = 1 WHERE role = 'admin';

-- 现有非 admin 用户加入默认项目（viewer 角色 id=1）
INSERT INTO group_member (project_id, user_id, role_id)
SELECT 1, u.id, 1 FROM sys_user u WHERE u.role = 'user' AND u.is_super_admin = 0;

-- 现有服务归属默认项目
UPDATE deploy_service SET project_id = 1 WHERE project_id = 0;

-- admin 密码迁移为 BCrypt（原始密码：admin123）
UPDATE sys_user SET password = '$2a$10$IwflDmGocTRo44hU9ZvAZeFpZLc1cXd7z/eluLZPPBFtsWRct5GyK' WHERE username = 'admin';


-- ============================================
-- 阶段 5: deployName + remark 字段
-- ============================================

ALTER TABLE deploy_service ADD COLUMN deploy_name VARCHAR(63) NOT NULL AFTER name;
ALTER TABLE deploy_service ADD UNIQUE KEY uk_deploy_name (deploy_name);
ALTER TABLE deploy_service ADD COLUMN remark VARCHAR(500) DEFAULT NULL COMMENT '服务备注' AFTER deploy_name;

-- 已有服务的 deploy_name 回填：使用 id 拼接生成合法值
UPDATE deploy_service SET deploy_name = CONCAT('service-', id) WHERE deploy_name = '';
