package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("deploy_service")
public class DeployService {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;        // 所属项目 ID
    private String name;           // 服务名称
    private Integer port;          // 暴露端口
    private String extraPorts;     // 额外端口映射 JSON: [{"hostPort":9090,"containerPort":9090}]
    private String volumeDir;      // 挂载目录
    private String serviceType;    // 服务类型：backend / frontend / fullstack
    private String serviceConfig;  // 结构化配置 JSON
    private String status;         // running, stopped
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
