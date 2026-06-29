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
}
