CREATE DATABASE IF NOT EXISTS flowops DEFAULT CHARACTER SET utf8mb4;

USE flowops;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS deploy_service (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    port INT NOT NULL,
    volume_dir VARCHAR(255),
    dockerfile TEXT,
    docker_compose TEXT,
    status VARCHAR(20) DEFAULT 'stopped',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS deploy_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_id BIGINT NOT NULL,
    status VARCHAR(20),
    log_path VARCHAR(255),
    remark VARCHAR(255),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 默认管理员账户: admin/admin123
INSERT INTO sys_user (username, password, role) VALUES ('admin', 'admin123', 'admin');
