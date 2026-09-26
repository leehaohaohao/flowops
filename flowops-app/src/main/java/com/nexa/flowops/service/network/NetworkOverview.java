package com.nexa.flowops.service.network;

import com.nexa.flowops.entity.DockerNetwork;

/**
 * 网络列表视图（B2 领域对象，API 层再映射为对外 VO）
 *
 * @param dockerStatus PRESENT=主节点 Docker 中存在；MISSING=已登记但 Docker 实体缺失
 */
public record NetworkOverview(DockerNetwork network,
                              String dockerStatus,
                              long grantedProjectCount,
                              long serviceRefCount) {
}
