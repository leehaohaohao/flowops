package com.nexa.flowops.service.generate;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static com.nexa.flowops.service.generate.YamlHelper.getString;

@Component
@Order(2)
public class NginxConfGenerator implements ConfigGenerator {

    @Override
    public boolean supports(DeployContext context) {
        String type = context.getServiceType();
        return "frontend".equals(type) || "fullstack".equals(type);
    }

    @Override
    public void generate(DeployContext context) throws IOException {
        String customNginx = context.getCustomNginx();
        int nginxListenPort = context.getNginxListenPort();
        List<Map<String, Object>> proxyRules = context.getProxyRules();

        String content;
        if (customNginx != null && !customNginx.isEmpty()) {
            content = customNginx;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("server {\n");
            sb.append("    listen ").append(nginxListenPort).append(";\n");
            sb.append("    server_name localhost;\n");
            sb.append("\n");
            sb.append("    root /usr/share/nginx/html;\n");
            sb.append("    index index.html;\n");
            sb.append("\n");
            sb.append("    # 静态资源缓存\n");
            sb.append("    location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {\n");
            sb.append("        expires 30d;\n");
            sb.append("        add_header Cache-Control \"public, immutable\";\n");
            sb.append("    }\n");

            if (proxyRules != null) {
                for (Map<String, Object> rule : proxyRules) {
                    String path = getString(rule, "path", "");
                    if (path.isEmpty()) continue;
                    if (!path.startsWith("/")) path = "/" + path;
                    if (!path.endsWith("/")) path = path + "/";

                    sb.append("\n");
                    sb.append("    # 反向代理: ").append(path).append("\n");
                    sb.append("    location ").append(path).append(" {\n");

                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> directives = (List<Map<String, String>>) rule.get("directives");
                    if (directives != null) {
                        for (Map<String, String> d : directives) {
                            String name = d.get("name");
                            String value = d.get("value");
                            if (name != null && !name.isEmpty()) {
                                sb.append("        ").append(name);
                                if (value != null && !value.isEmpty()) {
                                    sb.append(" ").append(value);
                                }
                                sb.append(";\n");
                            }
                        }
                    }

                    sb.append("    }\n");
                }
            }

            sb.append("\n");
            sb.append("    # SPA 路由\n");
            sb.append("    location / {\n");
            sb.append("        try_files $uri $uri/ /index.html;\n");
            sb.append("    }\n");
            sb.append("}\n");
            content = sb.toString();
        }

        Files.writeString(new File(context.getVolumeDir(), "default.conf").toPath(), content);
    }
}
