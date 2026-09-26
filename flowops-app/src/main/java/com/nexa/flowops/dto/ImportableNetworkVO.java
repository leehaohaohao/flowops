package com.nexa.flowops.dto;

import lombok.Data;

/**
 * 可导入的主节点 Docker 网络（未登记的用户自定义 bridge）
 */
@Data
public class ImportableNetworkVO {
    private String name;
    private String driver;
}
