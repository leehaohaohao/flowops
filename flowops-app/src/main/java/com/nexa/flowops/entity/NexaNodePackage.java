package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布包索引（阶段 2 P3）：主节点已校验并不可变存储的通用发布包。
 *
 * <p>包文件本体在 {@code {flowops.runner-package.store-dir}/<sha256>.tar.gz}；
 * 主键 {@code sha256} 是对 {@code .tar.gz} 文件字节计算的摘要（小写十六进制 64 位），
 * 既是存储文件名也是分发后在目标机的正式文件名，不允许覆盖。
 *
 * <p>表内不保存 runnerId、主节点地址、注册 token 或任何节点凭据：
 * 通用包按契约（{@code docs/runner-package-format.md}）与节点凭据完全分离。
 */
@Data
@TableName("nexa_node_package")
public class NexaNodePackage {

    /** 包摘要（.tar.gz 字节的 sha256，hex） */
    @TableId(type = IdType.INPUT)
    private String sha256;
    /** 上传文件名 flowops-executor-<version>-linux-amd64.tar.gz */
    private String fileName;
    /** manifest.package.version（dev 包带 -dev 后缀） */
    private String version;
    /** manifest.package.os（固定 linux） */
    private String os;
    /** manifest.package.arch（固定 amd64） */
    private String arch;
    /** manifest.packageFormatVersion（当前 1） */
    private Integer formatVersion;
    /** 压缩包字节数 */
    private Long sizeBytes;
    /** manifest.package.gitCommit（40 位小写十六进制） */
    private String gitCommit;
    /** manifest.image.reference */
    private String imageReference;
    /** manifest.image.imageId（sha256:<64hex>） */
    private String imageId;
    /** manifest.image.dockerCliVersion */
    private String dockerCliVersion;
    /** manifest.image.composeVersion */
    private String composeVersion;
    /** 上传者用户名 */
    private String uploadedBy;
    /** 上传时间 */
    private LocalDateTime uploadedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
