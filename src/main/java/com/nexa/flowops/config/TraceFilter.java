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

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // 生成 traceId
        String traceId = httpReq.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put("traceId", traceId);
        httpResp.setHeader("X-Trace-Id", traceId);

        // 包装请求/响应以支持 body 缓存
        ContentCachingRequestWrapper reqWrapper = new ContentCachingRequestWrapper(httpReq);
        ContentCachingResponseWrapper respWrapper = new ContentCachingResponseWrapper(httpResp);

        long startTime = System.currentTimeMillis();
        String method = httpReq.getMethod();
        String uri = httpReq.getRequestURI();
        String query = httpReq.getQueryString();
        String url = query != null ? uri + "?" + query : uri;

        // 请求日志
        log.info("[{}] --> {} {}", traceId, method, url);

        try {
            chain.doFilter(reqWrapper, respWrapper);
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            int status = respWrapper.getStatus();

            // 响应日志
            String statusText = status >= 400 ? "FAIL" : "OK";
            if (status >= 400) {
                log.error("[{}] <-- {} {} {} {}ms", traceId, statusText, method, status, elapsed);
            } else {
                log.info("[{}] <-- {} {} {} {}ms", traceId, statusText, method, status, elapsed);
            }

            // 输出响应 body（错误时）
            if (status >= 400) {
                byte[] body = respWrapper.getContentAsByteArray();
                if (body.length > 0) {
                    log.error("[{}] body: {}", traceId,
                            new String(body, StandardCharsets.UTF_8).substring(0, Math.min(body.length, 500)));
                }
            }

            respWrapper.copyBodyToResponse();
            MDC.clear();
        }
    }
}
