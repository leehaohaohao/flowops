-- FlowOps 权限模块 - 建表语句
-- 集成方需在自己的数据库中执行此脚本

-- 项目（合并了原 project_group 和 project）
CREATE TABLE IF NOT EXISTS project (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_default  TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为默认项目（不可删除）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 项目成员（原 group_member，group_id 改为 project_id）
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
