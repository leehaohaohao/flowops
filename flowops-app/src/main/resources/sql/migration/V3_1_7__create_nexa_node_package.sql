-- 3.1.7: 子节点执行器发布包索引（阶段 2 P3：上传、校验、按摘要不可变存储）
-- 包文件本体在 {flowops.runner-package.store-dir}/<sha256>.tar.gz（默认 /data/flowops/runner-packages）；
-- 本表只保存索引与 manifest 元数据。主键即包摘要（小写十六进制 64 位），包不可覆盖、不可变，
-- 相同摘要重复上传只返回既有记录（existing=true）。
-- 包内容不含 runnerId、主节点地址、注册 token 或任何节点凭据；本表也不保存任何凭据。
CREATE TABLE IF NOT EXISTS nexa_node_package (
    sha256             CHAR(64)     NOT NULL COMMENT '包摘要（.tar.gz 文件字节的 sha256，hex）',
    file_name          VARCHAR(200) NOT NULL COMMENT '文件名 flowops-executor-<version>-linux-amd64.tar.gz',
    version            VARCHAR(64)  NOT NULL COMMENT 'manifest.package.version（dev 包带 -dev 后缀）',
    os                 VARCHAR(16)  NOT NULL COMMENT 'manifest.package.os（固定 linux）',
    arch               VARCHAR(16)  NOT NULL COMMENT 'manifest.package.arch（固定 amd64）',
    format_version     INT          NOT NULL COMMENT 'manifest.packageFormatVersion（当前 1）',
    size_bytes         BIGINT       NOT NULL COMMENT '压缩包字节数',
    git_commit         VARCHAR(40)  DEFAULT NULL COMMENT 'manifest.package.gitCommit',
    image_reference    VARCHAR(200) DEFAULT NULL COMMENT 'manifest.image.reference',
    image_id           VARCHAR(80)  DEFAULT NULL COMMENT 'manifest.image.imageId',
    docker_cli_version VARCHAR(32)  DEFAULT NULL COMMENT '镜像内 docker CLI 版本（manifest）',
    compose_version    VARCHAR(32)  DEFAULT NULL COMMENT '镜像内 compose 版本（manifest）',
    uploaded_by        VARCHAR(64)  DEFAULT NULL COMMENT '上传者用户名',
    uploaded_at        DATETIME     NOT NULL COMMENT '上传时间',
    create_time        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (sha256),
    KEY idx_nexa_node_package_uploaded_at (uploaded_at)
);
