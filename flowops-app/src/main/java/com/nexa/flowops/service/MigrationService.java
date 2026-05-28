package com.nexa.flowops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.entity.PortMapping;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final DeployServiceMapper serviceMapper;
    private final ObjectMapper objectMapper;

    public MigrationService(DeployServiceMapper serviceMapper, ObjectMapper objectMapper) {
        this.serviceMapper = serviceMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 迁移 portMappings：清理 serviceConfig 中的废弃端口字段，
     * 对没有 portMappings 的服务初始化为空数组。
     */
    public int migratePortMappings() {
        List<DeployService> services = serviceMapper.selectList(null);
        int migrated = 0;

        for (DeployService service : services) {
            boolean needsUpdate = false;

            // 清理 serviceConfig 中已废弃的端口字段
            if (cleanServiceConfigPortFields(service)) {
                needsUpdate = true;
            }

            // 没有 portMappings 的初始化为空数组
            if (service.getPortMappings() == null || service.getPortMappings().isBlank()) {
                service.setPortMappings("[]");
                needsUpdate = true;
            }

            if (needsUpdate) {
                serviceMapper.updateById(service);
                migrated++;
                log.info("[{}] 迁移完成, portMappings={}", service.getName(), service.getPortMappings());
            }
        }

        return migrated;
    }

    @SuppressWarnings("unchecked")
    private boolean cleanServiceConfigPortFields(DeployService service) {
        String configJson = service.getServiceConfig();
        if (configJson == null || configJson.isBlank()) return false;

        try {
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
            boolean changed = false;

            if (config.containsKey("backend")) {
                Map<String, Object> backend = (Map<String, Object>) config.get("backend");
                if (backend != null && backend.remove("containerPort") != null) {
                    changed = true;
                }
            }

            if (config.containsKey("frontend")) {
                Map<String, Object> frontend = (Map<String, Object>) config.get("frontend");
                if (frontend != null) {
                    if (frontend.remove("containerPort") != null) changed = true;
                    if (frontend.remove("frontendPort") != null) changed = true;
                }
            }

            if (changed) {
                service.setServiceConfig(objectMapper.writeValueAsString(config));
            }
            return changed;
        } catch (Exception e) {
            log.warn("[{}] 清理 serviceConfig 端口字段失败: {}", service.getName(), e.getMessage());
            return false;
        }
    }
}
