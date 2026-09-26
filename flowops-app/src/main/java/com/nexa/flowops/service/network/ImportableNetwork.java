package com.nexa.flowops.service.network;

/**
 * 主节点 Docker 中尚未登记、可导入的用户自定义 bridge 网络
 */
public record ImportableNetwork(String name, String driver) {
}
