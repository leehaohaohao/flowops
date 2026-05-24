package com.nexa.flowops.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组主管权限校验注解。
 * 超级管理员自动跳过检查。
 *
 * 示例：
 *   @RequireGroupSupervisor("groupId")       — 从路径变量取 groupId
 *   @RequireGroupSupervisor("params.groupId") — 从 @RequestBody DTO 中取 groupId
 *   @RequireGroupSupervisor("project:id")     — 从路径变量取 id，反查项目的 groupId
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireGroupSupervisor {

    /**
     * groupId 的来源表达式：
     *   "groupId"         — 路径变量
     *   "params.groupId"  — 从 @RequestBody DTO 中取 groupId
     *   "project:id"      — 从路径变量 id 反查项目，取其 groupId
     */
    String value();
}
