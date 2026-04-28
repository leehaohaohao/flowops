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
    private String status;         // success, failed
    private String logPath;        // 日志文件路径
    private String remark;         // 备注
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}