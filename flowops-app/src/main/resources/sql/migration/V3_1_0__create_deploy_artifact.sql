-- 3.1.0: 产物注册表（远程部署产物标准化）
-- 产物文件仍存储在主节点 {volumeDir}，注册表记录元数据（storage_path 指向现位置，不搬迁存储）
CREATE TABLE IF NOT EXISTS deploy_artifact (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    service_id   BIGINT       NOT NULL,
    deploy_name  VARCHAR(63)  NOT NULL,
    type         VARCHAR(16)  NOT NULL COMMENT 'JAR / DIST / BINARY',
    file_name    VARCHAR(255) NOT NULL,
    storage_path VARCHAR(512) NOT NULL COMMENT '主节点实际存储路径',
    size         BIGINT       NOT NULL COMMENT '单文件=文件字节数；DIST=打包流字节数',
    checksum     CHAR(64)     NOT NULL COMMENT 'sha256(hex)，传输后校验',
    version      INT          NOT NULL COMMENT '每次上传 +1，支持多版本/回滚',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_service (service_id, type)
);
