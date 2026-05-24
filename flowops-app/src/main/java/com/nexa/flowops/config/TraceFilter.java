package com.nexa.flowops.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class TraceFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    /** 需要记录请求体的 Content-Type 前缀 */
    private static final String[] LOGGABLE_CONTENT_TYPES = {
            "application/json", "application/x-www-form-urlencoded", "text/"
    };

    /** 不记录日志的静态资源路径 */
    private static final String[] STATIC_PREFIXES = {
            "/css/", "/js/", "/img/", "/fonts/", "/favicon"
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // 生成 traceId（traceId 已在 logback pattern 中通过 %X{traceId} 输出，消息体中不再重复）
        String traceId = httpReq.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put("traceId", traceId);
        httpResp.setHeader("X-Trace-Id", traceId);

        // 静态资源不记录日志
        String uri = httpReq.getRequestURI();
        boolean isStatic = false;
        for (String prefix : STATIC_PREFIXES) {
            if (uri.startsWith(prefix)) {
                isStatic = true;
                break;
            }
        }

        // 包装请求/响应以支持 body 缓存
        ContentCachingRequestWrapper reqWrapper = new ContentCachingRequestWrapper(httpReq);
        ContentCachingResponseWrapper respWrapper = new ContentCachingResponseWrapper(httpResp);

        long startTime = System.currentTimeMillis();
        String method = httpReq.getMethod();
        String query = httpReq.getQueryString();
        String url = query != null ? uri + "?" + query : uri;
        String clientIp = getClientIp(httpReq);

        // 请求日志
        if (!isStatic) {
            log.info(">>> {} {} from {}", method, url, clientIp);
            logRequestBody(method, reqWrapper);
        }

        try {
            chain.doFilter(reqWrapper, respWrapper);
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            int status = respWrapper.getStatus();

            if (!isStatic) {
                if (status >= 500) {
                    log.error("<<< {} {} status={} elapsed={}ms", method, url, status, elapsed);
                } else if (status >= 400) {
                    log.warn("<<< {} {} status={} elapsed={}ms", method, url, status, elapsed);
                } else {
                    log.info("<<< {} {} status={} elapsed={}ms", method, url, status, elapsed);
                }
                // 4xx/5xx 输出响应体便于排查
                if (status >= 400) {
                    logResponseBody(respWrapper, status);
                }
            }

            respWrapper.copyBodyToResponse();
            MDC.clear();
        }
    }

    private void logRequestBody(String method, ContentCachingRequestWrapper reqWrapper) {
        // 只对有请求体的方法（POST/PUT/PATCH）记录 body
        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            return;
        }
        String contentType = reqWrapper.getContentType();
        if (contentType == null) return;

        boolean loggable = false;
        for (String prefix : LOGGABLE_CONTENT_TYPES) {
            if (contentType.toLowerCase().startsWith(prefix)) {
                loggable = true;
                break;
            }
        }
        if (!loggable) return;

        byte[] body = reqWrapper.getContentAsByteArray();
        if (body.length == 0) return;

        String bodyStr = new String(body, StandardCharsets.UTF_8);
        // 截断过长的 body（如 base64 文件上传）
        if (bodyStr.length() > 1024) {
            bodyStr = bodyStr.substring(0, 1024) + "...(truncated)";
        }
        log.info("    body: {}", bodyStr);
    }

    private void logResponseBody(ContentCachingResponseWrapper respWrapper, int status) {
        byte[] body = respWrapper.getContentAsByteArray();
        if (body.length == 0) return;

        String bodyStr = new String(body, StandardCharsets.UTF_8);
        if (bodyStr.length() > 1024) {
            bodyStr = bodyStr.substring(0, 1024) + "...(truncated)";
        }

        if (status >= 500) {
            log.error("    resp: {}", bodyStr);
        } else {
            log.warn("    resp: {}", bodyStr);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            // 取第一个（客户端真实 IP）
            int comma = ip.indexOf(',');
            return comma > 0 ? ip.substring(0, comma).trim() : ip;
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }
        return request.getRemoteAddr();
    }
}
