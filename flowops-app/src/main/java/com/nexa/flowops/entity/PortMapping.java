package com.nexa.flowops.entity;

import lombok.Data;

@Data
public class PortMapping {
    private Integer hostPort;       // 宿主机端口（expose=true 时可为 null）
    private Integer containerPort;  // 容器端口（必填）
    private String protocol;        // "tcp" (默认) / "udp"
    private String label;           // UI 显示标签
    private boolean primary;        // 主端口映射（每种类型有且仅有一个）
    private boolean expose;         // true = 仅内部网络可达，false = 映射到宿主机
    private String target;          // 仅 fullstack: "backend" / "frontend"，默认 "backend"
}
