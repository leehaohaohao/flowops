package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主节点 Docker 用户自定义 bridge 网络登记。
 *
 * <p>本期网络仅属主节点：{@link #ownerType} 区分主节点与未来 runner，
 * 不使用 runner ID 代表主节点（MASTER 行 {@link #ownerId} 为空串）。
 */
@Data
@TableName("docker_network")
public class DockerNetwork {

    /** source：平台创建 */
    public static final String SOURCE_MANAGED = "MANAGED";
    /** source：导入既有网络 */
    public static final String SOURCE_IMPORTED = "IMPORTED";

    /** owner_type：主节点 */
    public static final String OWNER_TYPE_MASTER = "MASTER";
    /** owner_type：子节点（后续阶段使用） */
    public static final String OWNER_TYPE_RUNNER = "RUNNER";

    /** 主节点归属使用空串（配合唯一键 (name, owner_type, owner_id)） */
    public static final String MASTER_OWNER_ID = "";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** Docker 网络名 */
    private String name;
    /** 界面显示名 */
    private String displayName;
    /** MANAGED / IMPORTED */
    private String source;
    /** MASTER / RUNNER */
    private String ownerType;
    /** 归属节点标识；MASTER 为空串 */
    private String ownerId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
