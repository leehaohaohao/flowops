package com.nexa.flowops.common.anno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源级权限校验注解。
 * 超级管理员自动跳过检查。
 *
 * 示例：
 *   @RequirePermission("EDIT_CONFIG")                                    — 从路径变量 {id} 查服务的 projectId
 *   @RequirePermission(value = "EDIT_CONFIG", projectId = "params.projectId") — 从 @RequestBody DTO 中取 projectId
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 需要的权限码，如 DEPLOY、STOP、EDIT_CONFIG */
    String value();

    /**
     * projectId 的来源表达式，支持：
     *   "{id}"               — 路径变量 id，通过 serviceId 反查 projectId
     *   "params.projectId"   — 从 @RequestBody DTO 中取 projectId 字段
     *   "params.groupId"     — 从 @RequestBody DTO 中取 groupId 字段（用于项目管理）
     * 留空时默认从路径变量 {id} 反查
     */
    String projectId() default "";
}
