package com.nexa.flowops.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 项目主管权限校验注解。
 * 超级管理员自动跳过检查。
 *
 * 示例：
 *   @RequireProjectSupervisor("projectId")       — 从路径变量取 projectId
 *   @RequireProjectSupervisor("params.projectId") — 从 @RequestBody DTO 中取 projectId
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireProjectSupervisor {

    /**
     * projectId 的来源表达式：
     *   "projectId"         — 路径变量
     *   "params.projectId"  — 从 @RequestBody DTO 中取 projectId
     */
    String value();
}
