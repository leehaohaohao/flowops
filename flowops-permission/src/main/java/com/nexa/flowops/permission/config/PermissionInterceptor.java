package com.nexa.flowops.permission.config;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.common.RequireGroupSupervisor;
import com.nexa.flowops.common.RequirePermission;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionService permissionService;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // URL模式 -> 所需权限的映射
    private static final Map<String, String> DEPLOY_PERMISSIONS = Map.of(
            "POST:/api/deploy/start/", "DEPLOY",
            "POST:/api/deploy/stop/", "STOP",
            "POST:/api/deploy/restart/", "DEPLOY",
            "POST:/api/deploy/remove/", "DELETE",
            "POST:/api/deploy/upload/", "UPLOAD",
            "POST:/api/deploy/upload-dist/", "UPLOAD",
            "GET:/api/deploy/status/", "VIEW",
            "GET:/api/deploy/logs/", "VIEW"
    );

    private static final Pattern SERVICE_ID_IN_DEPLOY = Pattern.compile("/api/deploy/\\w+/(\\d+)");
    private static final Pattern SERVICE_ID_IN_SERVICES = Pattern.compile("/api/services/(\\d+)");

    // 需要主管权限的路径
    private static final Pattern GROUP_MANAGEMENT = Pattern.compile("/api/groups/(\\d+)/members");
    private static final Pattern PROJECT_CREATE = Pattern.compile("/api/projects");

    public PermissionInterceptor(PermissionService permissionService, SysUserMapper userMapper) {
        this.permissionService = permissionService;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        // 只拦截 API 请求
        if (!uri.startsWith("/api/")) return true;

        // OPTIONS 预检请求放行
        if ("OPTIONS".equals(method)) return true;

        // 如果 Controller 方法上有权限注解，交给 PermissionAspect 处理
        if (handler instanceof HandlerMethod hm) {
            if (hm.hasMethodAnnotation(RequirePermission.class)
                    || hm.hasMethodAnnotation(RequireGroupSupervisor.class)) {
                return true;
            }
        }

        // 获取当前用户
        String username;
        try {
            username = StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            return true; // 未登录，交给 Sa-Token 处理
        }
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) return true;

        // 超级管理员放行所有
        if (user.getIsSuperAdmin() == 1) return true;

        // --- 以下为没有注解的旧接口兜底检查 ---

        // 1. 检查 deploy 接口权限
        if (uri.startsWith("/api/deploy/")) {
            return checkDeployPermission(method, uri, user.getId(), response);
        }

        // 2. 检查 services 接口权限
        if (uri.startsWith("/api/services/")) {
            return checkServicePermission(method, uri, user.getId(), response);
        }

        // 3. 检查项目组管理权限（需要主管或超级管理员）
        if (uri.startsWith("/api/groups/") && uri.contains("/members")) {
            return checkGroupManagement(uri, user.getId(), response);
        }

        // 4. 检查项目管理权限
        if (uri.startsWith("/api/projects")) {
            return checkProjectManagement(method, uri, user.getId(), response);
        }

        return true;
    }

    private boolean checkDeployPermission(String method, String uri, Long userId,
                                          HttpServletResponse response) throws Exception {
        String key = method + ":" + extractBasePath(uri);
        String requiredPerm = null;
        for (Map.Entry<String, String> entry : DEPLOY_PERMISSIONS.entrySet()) {
            if (key.startsWith(entry.getKey())) {
                requiredPerm = entry.getValue();
                break;
            }
        }
        if (requiredPerm == null) return true;

        Matcher matcher = SERVICE_ID_IN_DEPLOY.matcher(uri);
        if (!matcher.find()) return true;

        Long serviceId = Long.parseLong(matcher.group(1));
        if (!permissionService.checkServicePermission(userId, serviceId, requiredPerm)) {
            writeForbidden(response);
            return false;
        }
        return true;
    }

    private boolean checkServicePermission(String method, String uri, Long userId,
                                           HttpServletResponse response) throws Exception {
        // /api/services/list 不在这里拦截，由 Controller 层过滤
        if (uri.equals("/api/services/list")) return true;

        // /api/services/create 需要 EDIT_CONFIG
        if ("POST".equals(method) && uri.equals("/api/services/create")) {
            // create 的 projectId 在 body 里，交给 Controller 处理
            return true;
        }

        Matcher matcher = SERVICE_ID_IN_SERVICES.matcher(uri);
        if (!matcher.find()) return true;

        Long serviceId = Long.parseLong(matcher.group(1));
        String requiredPerm;
        if ("GET".equals(method)) {
            requiredPerm = "VIEW";
        } else if ("PUT".equals(method)) {
            requiredPerm = "EDIT_CONFIG";
        } else if ("DELETE".equals(method)) {
            requiredPerm = "DELETE";
        } else {
            return true;
        }

        if (!permissionService.checkServicePermission(userId, serviceId, requiredPerm)) {
            writeForbidden(response);
            return false;
        }
        return true;
    }

    private boolean checkGroupManagement(String uri, Long userId,
                                         HttpServletResponse response) throws Exception {
        Matcher matcher = GROUP_MANAGEMENT.matcher(uri);
        if (!matcher.find()) return true;

        Long groupId = Long.parseLong(matcher.group(1));
        if (!permissionService.isSupervisor(userId, groupId)) {
            writeForbidden(response);
            return false;
        }
        return true;
    }

    private boolean checkProjectManagement(String method, String uri, Long userId,
                                           HttpServletResponse response) throws Exception {
        // 项目创建/编辑/删除需要主管权限或超级管理员
        if ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)) {
            // 需要在 Controller 层检查 groupId 对应的主管权限
            // 这里先放行，Controller 层做细粒度检查
            return true;
        }
        return true;
    }

    private String extractBasePath(String uri) {
        // /api/deploy/start/123 -> /api/deploy/start/
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash > 0) {
            return uri.substring(0, lastSlash + 1);
        }
        return uri;
    }

    private void writeForbidden(HttpServletResponse response) throws Exception {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(403, "权限不足")));
    }
}
