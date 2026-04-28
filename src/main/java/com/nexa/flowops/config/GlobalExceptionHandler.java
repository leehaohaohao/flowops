package com.nexa.flowops.config;

import cn.dev33.satoken.exception.NotLoginException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    @ResponseBody
    public Object handleNotLogin(HttpServletRequest request, HttpServletResponse response) {
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(401);
            return Map.of("code", 401, "msg", "未登录，请重新登录");
        }
        try {
            response.sendRedirect("/login");
        } catch (Exception ignored) {
        }
        return null;
    }
}
