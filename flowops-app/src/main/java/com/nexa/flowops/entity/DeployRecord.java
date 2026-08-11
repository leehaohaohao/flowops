package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("deploy_record")
public class DeployRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long serviceId;
    private String status;         // success, failed, pending
    private String logPath;        // 日志文件路径
    private String remark;         // 备注
    private String nodeId;         // 执行节点 runnerId（null=本机执行）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}