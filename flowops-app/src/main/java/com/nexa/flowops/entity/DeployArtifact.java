package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 产物注册表：记录上传产物的元数据（存储仍在主节点 volumeDir，storage_path 指向现位置）
 */
@Data
@TableName("deploy_artifact")
public class DeployArtifact {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long serviceId;
    private String deployName;
    /** JAR / DIST / BINARY */
    private String type;
    private String fileName;
    /** 主节点实际存储路径；DIST 为解压目录 */
    private String storagePath;
    /** 单文件=文件字节数；DIST=确定性打包流字节数 */
    private Long size;
    /** sha256(hex)，传输后校验 */
    private String checksum;
    /** 每次上传 +1 */
    private Integer version;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
