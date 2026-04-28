package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("deploy_service")
public class DeployService {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;           // 服务名称
    private Integer port;           // 暴露端口
    private String volumeDir;       // 挂载目录
    private String dockerfile;      // Dockerfile 内容
    private String dockerCompose;  // docker-compose.yml 内容
    private String status;          // running, stopped
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}