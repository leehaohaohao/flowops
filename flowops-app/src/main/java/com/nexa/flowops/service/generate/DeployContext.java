package com.nexa.flowops.service.generate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.entity.PortMapping;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.nexa.flowops.service.generate.YamlHelper.*;

@Data
public class DeployContext {

    private String volumeDir;
    private String serviceType;
    private DeployService service;

    private Map<String, Object> backendConfig;
    private Map<String, Object> frontendConfig;
    private List<PortMapping> portMappings;

    // nginx 专属（frontend/fullstack 时有值）
    private String proxyTarget;
    private List<Map<String, Object>> proxyRules;
    private String customNginx;
    private int nginxListenPort;

    public static DeployContext from(DeployService service, ObjectMapper objectMapper) {
        DeployContext ctx = new DeployContext();
        ctx.service = service;
        ctx.volumeDir = service.getVolumeDir();
        ctx.serviceType = service.getServiceType();

        // 解析 serviceConfig
        Map<String, Object> config = parseServiceConfig(service.getServiceConfig(), objectMapper);
        ctx.backendConfig = config.containsKey("backend")
                ? (Map<String, Object>) config.get("backend") : null;
        ctx.frontendConfig = config.containsKey("frontend")
                ? (Map<String, Object>) config.get("frontend") : null;

        // 解析 portMappings
        ctx.portMappings = parsePortMappings(service.getPortMappings(), objectMapper);

        // 计算 nginx 相关字段
        if ("frontend".equals(ctx.serviceType) || "fullstack".equals(ctx.serviceType)) {
            ctx.proxyRules = ctx.frontendConfig != null && ctx.frontendConfig.containsKey("proxyRules")
                    ? (List<Map<String, Object>>) ctx.frontendConfig.get("proxyRules")
                    : Collections.emptyList();
            ctx.customNginx = ctx.frontendConfig != null
                    ? (String) ctx.frontendConfig.get("customNginxConfig") : null;
            ctx.nginxListenPort = ctx.frontendConfig != null
                    ? getInt(ctx.frontendConfig, "nginxListenPort", 80) : 80;

            if ("fullstack".equals(ctx.serviceType)) {
                ctx.proxyTarget = computeFullstackProxyTarget(ctx.backendConfig, ctx.portMappings);
            } else {
                ctx.proxyTarget = ctx.frontendConfig != null && ctx.frontendConfig.containsKey("backendUrl")
                        ? (String) ctx.frontendConfig.get("backendUrl") : "http://localhost:8080";
            }
        }

        return ctx;
    }

    private static String computeFullstackProxyTarget(Map<String, Object> backendConfig,
                                                       List<PortMapping> portMappings) {
        int containerPort = getInt(backendConfig, "containerPort", 8080);
        if (!portMappings.isEmpty()) {
            List<PortMapping> backendMappings = getMappingsByTarget(portMappings, "backend");
            for (PortMapping pm : backendMappings) {
                if (pm.isExpose()) {
                    containerPort = pm.getContainerPort();
                    break;
                }
            }
        }
        return "http://backend:" + containerPort;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseServiceConfig(String json, ObjectMapper objectMapper) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static List<PortMapping> parsePortMappings(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
