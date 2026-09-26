-- 3.1.2: 主节点 Docker 用户自定义 bridge 网络登记与项目授权
-- 归属：本期网络仅属主节点。owner_type 区分主节点与未来 runner，
--       不使用 runner ID 代表主节点（MASTER 行 owner_id 固定为空串）。
CREATE TABLE IF NOT EXISTS docker_network (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(128) NOT NULL COMMENT 'Docker 网络名',
    display_name VARCHAR(128) DEFAULT NULL COMMENT '界面显示名',
    source       VARCHAR(16)  NOT NULL COMMENT 'MANAGED=平台创建 / IMPORTED=导入既有',
    owner_type   VARCHAR(16)  NOT NULL DEFAULT 'MASTER' COMMENT '归属节点类型：MASTER / RUNNER',
    owner_id     VARCHAR(63)  NOT NULL DEFAULT '' COMMENT '归属节点标识；MASTER 为空串，未来 RUNNER 存 runnerId',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_network_owner (name, owner_type, owner_id)
);

-- 网络对项目的授权（超管授予；项目主管只能在已授权范围内设默认值）
CREATE TABLE IF NOT EXISTS network_project_grant (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    network_id  BIGINT NOT NULL,
    project_id  BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_network_project (network_id, project_id),
    KEY idx_grant_project (project_id),
    CONSTRAINT fk_grant_network FOREIGN KEY (network_id) REFERENCES docker_network (id)
);

-- 项目默认网络（仅用于新建服务预选；已存在服务不随之改变）
CREATE TABLE IF NOT EXISTS project_default_network (
    project_id  BIGINT PRIMARY KEY,
    network_id  BIGINT NOT NULL,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_project_default_network FOREIGN KEY (network_id) REFERENCES docker_network (id)
);
