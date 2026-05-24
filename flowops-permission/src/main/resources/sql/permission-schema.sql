-- FlowOps 权限模块 - 建表语句
-- 集成方需在自己的数据库中执行此脚本

-- 项目组
CREATE TABLE IF NOT EXISTS project_group (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_default  TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为默认项目组（不可删除）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 项目
CREATE TABLE IF NOT EXISTS project (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id    BIGINT NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_group_id (group_id)
);

-- 组成员
CREATE TABLE IF NOT EXISTS group_member (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id          BIGINT NOT NULL,
    user_id           BIGINT NOT NULL,
    role_id           BIGINT NOT NULL,
    extra_permissions VARCHAR(500) DEFAULT NULL COMMENT '额外权限码，逗号分隔',
    UNIQUE KEY uk_group_user (group_id, user_id),
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

-- 跨组项目授权
CREATE TABLE IF NOT EXISTS project_access (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    perm_code  VARCHAR(50) NOT NULL,
    UNIQUE KEY uk_user_project_perm (user_id, project_id, perm_code),
    INDEX idx_user_id (user_id)
);

-- 用户表（集成方若已有用户表，需添加 is_super_admin 字段）
-- CREATE TABLE IF NOT EXISTS sys_user (
--     id            BIGINT AUTO_INCREMENT PRIMARY KEY,
--     username      VARCHAR(50) NOT NULL UNIQUE,
--     password      VARCHAR(100) NOT NULL,
--     role          VARCHAR(20) DEFAULT 'user',
--     is_super_admin TINYINT(1) NOT NULL DEFAULT 0,
--     create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
--     update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
-- );
