package com.nexa.flowops.dto;

import lombok.Data;

@Data
public class NodeInfoVO {
    private String runnerId;
    private String hostname;
    private String ip;
    private String version;
    private long lastHeartbeatTime;
    private boolean online;
    private int runningTasks;      // 最近心跳上报的进行中任务数
    private double cpuUsage;       // 最近心跳上报的 CPU 使用率
    private double memoryUsage;    // 最近心跳上报的内存使用率
}
