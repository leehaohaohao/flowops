package com.nexa.flowops.config;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.RequireGroupSupervisor;
import com.nexa.flowops.common.RequirePermission;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.entity.Project;
import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.ProjectMapper;
import com.nexa.flowops.mapper.SysUserMapper;
import com.nexa.flowops.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

@Aspect
@Component
public class PermissionAspect {

    private final PermissionService permissionService;
    private final SysUserMapper userMapper;
    private final ProjectMapper projectMapper;

    public PermissionAspect(PermissionService permissionService, SysUserMapper userMapper,
                            ProjectMapper projectMapper) {
        this.permissionService = permissionService;
        this.userMapper = userMapper;
        this.projectMapper = projectMapper;
    }

    @Around("@annotation(requirePermission)")
    public Object checkPermission(ProceedingJoinPoint pjp, RequirePermission requirePermission) throws Throwable {
        SysUser user = getCurrentUser();
        if (user == null) return pjp.proceed();
        if (user.getIsSuperAdmin() == 1) return pjp.proceed();

        Long projectId = resolveProjectId(pjp, requirePermission.projectId());
        if (projectId == null) {
            return Result.fail(400, "缺少 projectId");
        }

        if (!permissionService.getEffectivePermissions(user.getId(), projectId)
                .contains(requirePermission.value())) {
            return Result.fail(403, "权限不足");
        }

        return pjp.proceed();
    }

    @Around("@annotation(requireSupervisor)")
    public Object checkGroupSupervisor(ProceedingJoinPoint pjp,
                                       RequireGroupSupervisor requireSupervisor) throws Throwable {
        SysUser user = getCurrentUser();
        if (user == null) return pjp.proceed();
        if (user.getIsSuperAdmin() == 1) return pjp.proceed();

        String expr = requireSupervisor.value();
        Long groupId;

        if (expr.startsWith("project:")) {
            // 从路径变量取 projectId，反查项目的 groupId
            String projectIdVar = expr.substring(8);
            Long projectId = resolveLong(pjp, projectIdVar);
            if (projectId == null) return Result.fail(400, "缺少项目 ID");
            Project project = projectMapper.selectById(projectId);
            if (project == null) return Result.fail(400, "项目不存在");
            groupId = project.getGroupId();
        } else {
            groupId = resolveLong(pjp, expr);
        }

        if (groupId == null) {
            return Result.fail(400, "缺少 groupId");
        }

        if (!permissionService.isSupervisor(user.getId(), groupId)) {
            return Result.fail(403, "权限不足");
        }

        return pjp.proceed();
    }

    private SysUser getCurrentUser() {
        try {
            String username = StpUtil.getLoginIdAsString();
            return userMapper.selectByUsername(username);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 projectId：
     *   留空 → 从路径变量 {id} 反查服务的 projectId
     *   "params.xxx" → 从 @RequestBody DTO 中取字段
     *   "xxx" → 从路径变量取
     */
    private Long resolveProjectId(ProceedingJoinPoint pjp, String expr) {
        if (expr.isEmpty()) {
            Long serviceId = resolveLong(pjp, "id");
            if (serviceId == null) return null;
            return permissionService.getProjectIdByServiceId(serviceId);
        }
        if (expr.startsWith("params.")) {
            String field = expr.substring(7);
            return getBodyField(pjp, field);
        }
        return resolveLong(pjp, expr);
    }

    /**
     * 解析长整型值：优先从路径变量取，其次从请求参数取
     */
    private Long resolveLong(ProceedingJoinPoint pjp, String name) {
        HttpServletRequest request = getRequest();
        if (request == null) return null;

        // 1. 路径变量
        Object attr = request.getAttribute("org.springframework.web.servlet.HandlerMapping.uriTemplateVariables");
        if (attr instanceof Map) {
            Object val = ((Map<?, ?>) attr).get(name);
            if (val != null) return parseLong(val);
        }

        // 2. 请求参数
        String param = request.getParameter(name);
        if (param != null) return parseLong(param);

        // 3. 从方法参数中按名称匹配（需要 -parameters 编译选项）
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < paramNames.length; i++) {
            if (name.equals(paramNames[i]) && args[i] instanceof Number) {
                return ((Number) args[i]).longValue();
            }
        }

        return null;
    }

    /**
     * 从 @RequestBody DTO 中通过反射取字段值
     */
    private Long getBodyField(ProceedingJoinPoint pjp, String fieldName) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Object[] args = pjp.getArgs();
        java.lang.annotation.Annotation[][] paramAnnotations = sig.getMethod().getParameterAnnotations();

        for (int i = 0; i < paramAnnotations.length; i++) {
            for (java.lang.annotation.Annotation ann : paramAnnotations[i]) {
                if (ann instanceof RequestBody) {
                    Object body = args[i];
                    if (body == null) return null;
                    // 先尝试 getter
                    try {
                        String getter = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                        Method m = body.getClass().getMethod(getter);
                        Object val = m.invoke(body);
                        return parseLong(val);
                    } catch (Exception ignored) {}
                    // 再尝试直接访问字段
                    try {
                        Field f = body.getClass().getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Object val = f.get(body);
                        return parseLong(val);
                    } catch (Exception ignored) {}
                    return null;
                }
            }
        }
        return null;
    }

    private HttpServletRequest getRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private Long parseLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        try {
            return Long.parseLong(String.valueOf(val));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
