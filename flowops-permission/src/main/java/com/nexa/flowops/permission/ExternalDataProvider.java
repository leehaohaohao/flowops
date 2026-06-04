package com.nexa.flowops.permission;

/**
 * 权限模块对外部数据的查询契约。
 * 集成方必须实现此接口，提供权限模块所需的业务数据。
 *
 * 新增跨模块查询时，在此接口添加 default 方法，
 * 默认返回 null/空值，不影响现有实现类。
 */
public interface ExternalDataProvider {

    /** 根据服务 ID 查询所属项目 ID */
    Long getProjectIdByServiceId(Long serviceId);

    /** 统计项目下的服务数量 */
    long countServicesByProjectId(Long projectId);

    /** 统计项目下运行中的服务数量 */
    default long countRunningByProjectId(Long projectId) { return 0; }
}
