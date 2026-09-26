package com.nexa.flowops.dto;

import lombok.Data;

/**
 * 创建 / 导入网络请求
 */
@Data
public class CreateNetworkRequest {
    /** Docker 网络名（严格校验） */
    private String name;
    /** 界面显示名，可空则回退为网络名 */
    private String displayName;
}
