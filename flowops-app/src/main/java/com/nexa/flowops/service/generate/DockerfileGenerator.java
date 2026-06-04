package com.nexa.flowops.service.generate;

import com.nexa.flowops.entity.PortMapping;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nexa.flowops.service.generate.YamlHelper.*;

@Component
@Order(1)
public class DockerfileGenerator implements ConfigGenerator {

    @Override
    public boolean supports(DeployContext context) {
        String type = context.getServiceType();
        return "backend".equals(type) || "fullstack".equals(type);
    }

    @Override
    public void generate(DeployContext context) throws IOException {
        Map<String, Object> backendConfig = context.getBackendConfig();
        List<PortMapping> portMappings = context.getPortMappings();
        String serviceType = context.getServiceType();

        String runtime = getString(backendConfig, "runtime", "java");

        String defaultBaseImage;
        String defaultStartupCommand;
        if ("go".equals(runtime)) {
            defaultBaseImage = "golang:1.26.3-alpine";
            defaultStartupCommand = "/app/app";
        } else {
            defaultBaseImage = "openjdk:17-jdk-slim";
            defaultStartupCommand = "java -jar /app/app.jar";
        }

        String baseImage = getString(backendConfig, "baseImage", defaultBaseImage);
        int containerPort = getInt(backendConfig, "containerPort", 8080);
        String startupCommand = getString(backendConfig, "startupCommand", defaultStartupCommand);

        StringBuilder sb = new StringBuilder();
        sb.append("FROM ").append(baseImage).append("\n");
        sb.append("WORKDIR /app\n");

        if ("go".equals(runtime)) {
            sb.append("COPY app /app/app\n");
            sb.append("RUN chmod +x /app/app\n");
        } else {
            sb.append("COPY app.jar /app/app.jar\n");
        }

        // EXPOSE
        if (!portMappings.isEmpty()) {
            Set<Integer> exposePorts = new LinkedHashSet<>();
            for (PortMapping pm : portMappings) {
                if ("fullstack".equals(serviceType) && !"backend".equals(pm.getTarget())) {
                    continue;
                }
                exposePorts.add(pm.getContainerPort());
            }
            sb.append("EXPOSE ").append(
                    exposePorts.stream().map(String::valueOf).collect(Collectors.joining(" "))
            ).append("\n");
        } else {
            sb.append("EXPOSE ").append(containerPort).append("\n");
        }

        // ENV
        if (backendConfig != null && backendConfig.containsKey("envVars")) {
            @SuppressWarnings("unchecked")
            Map<String, String> envVars = (Map<String, String>) backendConfig.get("envVars");
            if (envVars != null) {
                for (Map.Entry<String, String> entry : envVars.entrySet()) {
                    sb.append("ENV ").append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                }
            }
        }

        // ENTRYPOINT
        List<String> cmdParts = splitCommand(startupCommand);
        sb.append("ENTRYPOINT [");
        for (int i = 0; i < cmdParts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(cmdParts.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]\n");

        Files.writeString(new File(context.getVolumeDir(), "Dockerfile").toPath(), sb.toString());
    }
}
