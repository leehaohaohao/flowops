package com.nexa.flowops.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    public SaTokenConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Sa-Token 登录校验
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login",
                        "/auth/**",
                        "/error",
                        "/js/**",
                        "/css/**",
                        "/favicon.ico"
                );

        // 权限拦截器（仅拦截 API 请求）
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**");
    }
}
