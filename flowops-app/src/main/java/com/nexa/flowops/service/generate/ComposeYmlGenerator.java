package com.nexa.flowops.service.generate;

import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.entity.PortMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.logs.path}")
    private String logsBasePath;

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

        appendSharedNetworkSection(sb, context);

        Files.writeString(new File(context.getVolumeDir(), "docker-compose.yml").toPath(), sb.toString());
        log.info("[{}] 已生成 docker-compose.yml (type={}, sharedNetwork={})", context.getService().getName(),
                context.getServiceType(), context.getSharedNetworkName());
    }

    // ==================== 共享网络 ====================

    /**
     * 选中共享网络时：每个子服务同时加入私有 default 与 external 的 shared，并在 shared 上使用唯一别名。
     * 未选中共享网络（sharedNetworkName 为 null）时不输出任何 networks 段落，旧服务 Compose 结果不变。
     */
    private void appendSharedNetworkSection(StringBuilder sb, DeployContext context) {
        if (context.getSharedNetworkName() == null || context.getSharedNetworkName().isBlank()) {
            return;
        }
        sb.append("networks:\n");
        sb.append("  default: {}\n");
        sb.append("  shared:\n");
        sb.append("    external: true\n");
        sb.append("    name: ").append(context.getSharedNetworkName()).append("\n");
    }

    /** 子服务加入共享网络：私有 default + external shared（带唯一别名） */
    private void appendServiceNetworks(StringBuilder sb, DeployContext context, String role) {
        if (context.getSharedNetworkName() == null || context.getSharedNetworkName().isBlank()) {
            return;
        }
        sb.append("    networks:\n");
        sb.append("      default: {}\n");
        sb.append("      shared:\n");
        sb.append("        aliases:\n");
        sb.append("          - ").append(sharedAlias(context, role)).append("\n");
    }

    /** 共享网络上的唯一别名：flowops-svc-<服务ID>-<角色> */
    private String sharedAlias(DeployContext context, String role) {
        return "flowops-svc-" + context.getService().getId() + "-" + role;
    }

    // ==================== 新逻辑：从 portMappings 生成 ====================

    private void generateFromMappings(StringBuilder sb, DeployContext context) {
        String serviceType = context.getServiceType();
        List<PortMapping> portMappings = context.getPortMappings();
        DeployService service = context.getService();

        if ("backend".equals(serviceType)) {
            appendBackendService(sb, context, portMappings, context.getBackendConfig(), service);
        } else if ("frontend".equals(serviceType)) {
            appendFrontendService(sb, context, portMappings, context.getFrontendConfig(), false);
        } else if ("fullstack".equals(serviceType)) {
            List<PortMapping> backendMappings = getMappingsByTarget(portMappings, "backend");
            List<PortMapping> frontendMappings = getMappingsByTarget(portMappings, "frontend");
            if (backendMappings.isEmpty()) {
                backendMappings = getMappingsByTarget(portMappings, null);
            }
            appendBackendService(sb, context, backendMappings, context.getBackendConfig(), service);
            appendFrontendService(sb, context, frontendMappings, context.getFrontendConfig(), true);
        }
    }

    private void appendBackendService(StringBuilder sb, DeployContext context, List<PortMapping> portMappings,
                                       Map<String, Object> backendConfig, DeployService service) {
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
        appendAppLogVolume(sb, service, backendConfig);
        appendEnvironment(sb, backendConfig);
        sb.append("    restart: unless-stopped\n");
        appendServiceNetworks(sb, context, "backend");
    }

    private void appendFrontendService(StringBuilder sb, DeployContext context, List<PortMapping> portMappings,
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
        appendServiceNetworks(sb, context, "frontend");
    }

    // ==================== 旧逻辑：portMappings 为空时的 fallback ====================

    @SuppressWarnings("unchecked")
    private void generateLegacy(StringBuilder sb, DeployContext context) {
        String serviceType = context.getServiceType();
        Map<String, Object> backendConfig = context.getBackendConfig();
        Map<String, Object> frontendConfig = context.getFrontendConfig();
        DeployService service = context.getService();

        if ("backend".equals(serviceType)) {
            int containerPort = getInt(backendConfig, "containerPort", 8080);
            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(containerPort).append(":").append(containerPort).append("\"\n");
            appendVolumes(sb, backendConfig);
            appendAppLogVolume(sb, service, backendConfig);
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");
            appendServiceNetworks(sb, context, "backend");
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
            appendServiceNetworks(sb, context, "frontend");
        } else if ("fullstack".equals(serviceType)) {
            int containerPort = getInt(backendConfig, "containerPort", 8080);

            sb.append("  backend:\n");
            sb.append("    build: .\n");
            sb.append("    expose:\n");
            sb.append("      - \"").append(containerPort).append("\"\n");
            appendVolumes(sb, backendConfig);
            appendAppLogVolume(sb, service, backendConfig);
            appendEnvironment(sb, backendConfig);
            sb.append("    restart: unless-stopped\n");
            appendServiceNetworks(sb, context, "backend");

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
            appendServiceNetworks(sb, context, "frontend");
        }
    }

    /**
     * 若 serviceConfig 中配置了 appLogPath（容器内应用日志路径），
     * 为 backend 服务添加 volume 映射，将宿主机日志目录挂载到容器内
     */
    @SuppressWarnings("unchecked")
    private void appendAppLogVolume(StringBuilder sb, DeployService service, Map<String, Object> backendConfig) {
        if (backendConfig == null || !backendConfig.containsKey("appLogPath")) return;
        String appLogPath = String.valueOf(backendConfig.get("appLogPath")).trim();
        if (appLogPath.isEmpty()) return;

        String hostLogDir = logsBasePath + "/" + service.getProjectId() + "/" + service.getId() + "/app";
        // 检查 sb 末尾是否已有 volumes 块（由 appendVolumes 写入）
        String tail = sb.substring(Math.max(0, sb.length() - 200));
        if (tail.contains("    volumes:")) {
            sb.append("      - ").append(hostLogDir).append(":").append(appLogPath).append("\n");
        } else {
            sb.append("    volumes:\n");
            sb.append("      - ").append(hostLogDir).append(":").append(appLogPath).append("\n");
        }
    }
}
