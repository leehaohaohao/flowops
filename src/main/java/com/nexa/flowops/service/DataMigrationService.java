package com.nexa.flowops.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DataMigrationService implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public DataMigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        try {
            if (isMigrationNeeded()) {
                log.info("检测到需要执行权限系统迁移...");
                runMigration();
                log.info("权限系统迁移完成");
            } else {
                log.debug("权限系统迁移已执行，跳过");
            }
        } catch (Exception e) {
            log.error("权限系统迁移失败: {}", e.getMessage(), e);
        }
    }

    private boolean isMigrationNeeded() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM project_group", Integer.class);
            return count == null || count == 0;
        } catch (Exception e) {
            // 表不存在，需要迁移
            return true;
        }
    }

    private void runMigration() {
        // 1. 修改现有表
        executeSql("ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS is_super_admin TINYINT(1) NOT NULL DEFAULT 0 AFTER role");
        executeSql("ALTER TABLE deploy_service ADD COLUMN IF NOT EXISTS project_id BIGINT NOT NULL DEFAULT 1 AFTER id");

        // 2. 创建新表
        executeSql("CREATE TABLE IF NOT EXISTS project_group (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(100) NOT NULL, " +
                "description VARCHAR(500), " +
                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");

        executeSql("CREATE TABLE IF NOT EXISTS project (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "group_id BIGINT NOT NULL, " +
                "name VARCHAR(100) NOT NULL, " +
                "description VARCHAR(500), " +
                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                "INDEX idx_group_id (group_id))");

        executeSql("CREATE TABLE IF NOT EXISTS group_member (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "group_id BIGINT NOT NULL, " +
                "user_id BIGINT NOT NULL, " +
                "role_id BIGINT NOT NULL, " +
                "UNIQUE KEY uk_group_user (group_id, user_id), " +
                "INDEX idx_user_id (user_id))");

        executeSql("CREATE TABLE IF NOT EXISTS perm_definition (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "code VARCHAR(50) NOT NULL UNIQUE, " +
                "description VARCHAR(200))");

        executeSql("CREATE TABLE IF NOT EXISTS perm_role (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(50) NOT NULL, " +
                "is_preset TINYINT(1) NOT NULL DEFAULT 0, " +
                "group_id BIGINT DEFAULT NULL, " +
                "description VARCHAR(200), " +
                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "UNIQUE KEY uk_name_group (name, group_id))");

        executeSql("CREATE TABLE IF NOT EXISTS role_permission (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "role_id BIGINT NOT NULL, " +
                "perm_code VARCHAR(50) NOT NULL, " +
                "UNIQUE KEY uk_role_perm (role_id, perm_code))");

        executeSql("CREATE TABLE IF NOT EXISTS project_access (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "user_id BIGINT NOT NULL, " +
                "project_id BIGINT NOT NULL, " +
                "perm_code VARCHAR(50) NOT NULL, " +
                "UNIQUE KEY uk_user_project_perm (user_id, project_id, perm_code), " +
                "INDEX idx_user_id (user_id))");

        // 3. 插入种子数据
        seedData();

        // 4. 迁移现有数据
        migrateExistingData();
    }

    private void seedData() {
        // 权限定义
        executeSql("INSERT IGNORE INTO perm_definition (code, description) VALUES " +
                "('VIEW', '查看服务列表和状态'), " +
                "('DEPLOY', '部署/启动服务'), " +
                "('START', '启动已停止的服务'), " +
                "('STOP', '停止运行中的服务'), " +
                "('UPLOAD', '上传 JAR/dist 文件'), " +
                "('EDIT_CONFIG', '编辑服务配置'), " +
                "('DELETE', '删除服务')");

        // 预设角色
        executeSql("INSERT IGNORE INTO perm_role (id, name, is_preset, description) VALUES " +
                "(1, 'viewer', 1, '只读权限'), " +
                "(2, 'operator', 1, '运维操作权限'), " +
                "(3, 'editor', 1, '编辑权限'), " +
                "(4, 'admin', 1, '项目管理员，全部权限'), " +
                "(5, 'supervisor', 1, '项目组主管，可管理成员和项目')");

        // 角色-权限映射
        executeSql("INSERT IGNORE INTO role_permission (role_id, perm_code) VALUES " +
                "(1, 'VIEW'), " +
                "(2, 'VIEW'), (2, 'DEPLOY'), (2, 'START'), (2, 'STOP'), " +
                "(3, 'VIEW'), (3, 'DEPLOY'), (3, 'START'), (3, 'STOP'), (3, 'UPLOAD'), (3, 'EDIT_CONFIG'), " +
                "(4, 'VIEW'), (4, 'DEPLOY'), (4, 'START'), (4, 'STOP'), (4, 'UPLOAD'), (4, 'EDIT_CONFIG'), (4, 'DELETE'), " +
                "(5, 'VIEW'), (5, 'DEPLOY'), (5, 'START'), (5, 'STOP'), (5, 'UPLOAD'), (5, 'EDIT_CONFIG'), (5, 'DELETE'), " +
                "(5, 'MANAGE_MEMBERS'), (5, 'MANAGE_PROJECTS')");
    }

    private void migrateExistingData() {
        // 默认项目组和默认项目
        executeSql("INSERT IGNORE INTO project_group (id, name, description) VALUES (1, '默认项目组', '系统默认项目组')");
        executeSql("INSERT IGNORE INTO project (id, group_id, name, description) VALUES (1, 1, '默认项目', '系统默认项目')");

        // 现有 admin 用户标记为超级管理员
        executeSql("UPDATE sys_user SET is_super_admin = 1 WHERE role = 'admin' AND is_super_admin = 0");

        // 现有非 admin 用户加入默认项目组（viewer 角色 id=1）
        executeSql("INSERT IGNORE INTO group_member (group_id, user_id, role_id) " +
                "SELECT 1, u.id, 1 FROM sys_user u WHERE u.role = 'user' AND u.is_super_admin = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM group_member gm WHERE gm.user_id = u.id AND gm.group_id = 1)");

        // 现有服务归属默认项目
        executeSql("UPDATE deploy_service SET project_id = 1 WHERE project_id = 0");
    }

    private void executeSql(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            // 忽略重复执行导致的错误（如 Duplicate column）
            if (!e.getMessage().contains("Duplicate") && !e.getMessage().contains("duplicate")) {
                log.warn("SQL 执行警告: {} - {}", sql.substring(0, Math.min(sql.length(), 80)), e.getMessage());
            }
        }
    }
}
