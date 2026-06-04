package com.nexa.flowops.service.generate;

import com.nexa.flowops.entity.PortMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.nexa.flowops.service.generate.YamlHelper.*;

@Component
@Order(3)
public class ComposeYmlGenerator implements ConfigGenerator {

    private static final Logger log = LoggerFactory.getLogger(ComposeYmlGenerator.class);

    @Override
    public boolean supports(DeployContext context) {
        return true;
    }

    @Override
    public void generate(DeployContext context) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");

        if (!context.getPortMappings().isEmpty()) {
            generateFromMappings(sb, context);
        } else {
            generateLegacy(sb, context);
        }

        Files.writeString(new File(context.getVolumeDir(), "docker-compose.yml").toPath(), sb.toString());
        log.info("[{}] 已生成 docker-compose.yml (type={})", context.getService().getName(), context.getServiceType());
    }

    // ==================== 新逻辑：从 portMappings 生成 ====================

    private void generateFromMappings(StringBuilder sb, DeployContext context) {
        String serviceType = context.getServiceType();
        List<PortMapping> portMappings = context.getPortMappings();

        if ("backend".equals(serviceType)) {
            appendBackendService(sb, portMappings, context.getBackendConfig());
        } else if ("frontend".equals(serviceType)) {
            appendFrontendService(sb, portMappings, context.getFrontendConfig(), false);
        } else if ("fullstack".equals(serviceType)) {
            List<PortMapping> backendMappings = getMappingsByTarget(portMappings, "backend");
            List<PortMapping> frontendMappings = getMappingsByTarget(portMappings, "frontend");
            if (backendMappings.isEmpty()) {
                backendMappings = getMappingsByTarget(portMappings, null);
            }
            appendBackendService(sb, backendMappings, context.getBackendConfig());
            appendFrontendService(sb, frontendMappings, context.getFrontendConfig(), true);
        }
    }

    private void appendBackendService(StringBuilder sb, List<PortMapping> portMappings,
                                       Map<String, Object> backendConfig) {
        List<PortMapping> exposeMappings = getExposeMappings(portMappings);
        List<PortMapping> hostMappings = getHostPortMappings(portMappings);

        sb.append("  backend:\n");
        sb.append("    build: .\n");

        if (!exposeMappings.isEmpty()) {
            sb.append("    expose:\n");
            for (PortMapping pm : exposeMappings) {
                sb.append("      - \"").append(pm.getContainerPort()).append("\"\n");
            }
        }
        if (!hostMappings.isEmpty()) {
            sb.append("    ports:\n");
            for (PortMapping pm : hostMappings) {
                sb.append("      - \"").append(pm.getHostPort()).append(":").append(pm.getContainerPort()).append("\"\n");
            }
        }

        appendVolumes(sb, backendConfig);
        appendEnvironment(sb, backendConfig);
        sb.append("    restart: unless-stopped\n");
    }

    private void appendFrontendService(StringBuilder sb, List<PortMapping> portMappings,
                                        Map<String, Object> frontendConfig, boolean addDependsOn) {
        if (portMappings.isEmpty()) return;

        PortMapping primary = getPrimaryMapping(portMappings);
        if (primary == null) primary = portMappings.get(0);

        sb.append("  frontend:\n");
        sb.append("    image: ").append(getString(frontendConfig, "baseImage", "nginx:alpine")).append("\n");
        sb.append("    ports:\n");
        sb.append("      - \"").append(primary.getHostPort()).append(":").append(primary.getContainerPort()).append("\"\n");
        sb.append("    volumes:\n");
        sb.append("      - ./dist:/usr/share/nginx/html\n");
        sb.append("      - ./default.conf:/etc/nginx/conf.d/default.conf\n");
        if (addDependsOn) {
            sb.append("    depends_on:\n");
            sb.append("      - backend\n");
        }
        sb.append("    restart: unless-stopped\n");
    }

    // ==================== 旧逻辑：portMappings 为空时的 fallback ====================

    @SuppressWarnings("unchecked")
    private void generateLegacy(StringBuilder sb, DeployContext context) {
        String serviceType = context.getServiceType();
        Map<String, Object> backendConfig = context.getBackendConfig();
        Map<String, Object> frontendConfig = context.getFrontendConfig();

        if ("backend".equals(serviceType)) {
            int containerPort = getInt(backendConfig, "containerPort", 8080);
            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(containerPort).append(":").append(containerPort).append("\"\n");
            appendVolumes(sb, backendConfig);
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");
        } else if ("frontend".equals(serviceType)) {
            int nginxContainerPort = getInt(frontendConfig, "containerPort", 80);
            sb.append("  frontend:\n");
            sb.append("    image: ").append(getString(frontendConfig, "baseImage", "nginx:alpine")).append("\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(nginxContainerPort).append(":").append(nginxContainerPort).append("\"\n");
            sb.append("    volumes:\n");
            sb.append("      - ./dist:/usr/share/nginx/html\n");
            sb.append("      - ./default.conf:/etc/nginx/conf.d/default.conf\n");
            sb.append("    restart: unless-stopped\n");
        } else if ("fullstack".equals(serviceType)) {
            int containerPort = getInt(backendConfig, "containerPort", 8080);

            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    expose:\n");
            sb.append("      - \"").append(containerPort).append("\"\n");
            appendVolumes(sb, backendConfig);
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");

            int nginxContainerPort = getInt(frontendConfig, "containerPort", 80);
            int frontendHostPort = getInt(frontendConfig, "frontendPort", 80);

            sb.append("  frontend:\n");
            sb.append("    image: ").append(getString(frontendConfig, "baseImage", "nginx:alpine")).append("\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(frontendHostPort).append(":").append(nginxContainerPort).append("\"\n");
            sb.append("    volumes:\n");
            sb.append("      - ./dist:/usr/share/nginx/html\n");
            sb.append("      - ./default.conf:/etc/nginx/conf.d/default.conf\n");
            sb.append("    depends_on:\n");
            sb.append("      - backend\n");
            sb.append("    restart: unless-stopped\n");
        }
    }
}
