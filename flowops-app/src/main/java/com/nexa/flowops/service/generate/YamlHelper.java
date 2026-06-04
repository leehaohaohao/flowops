package com.nexa.flowops.service.generate;

import com.nexa.flowops.entity.PortMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class YamlHelper {

    private YamlHelper() {}

    // ==================== Map 安全读取 ====================

    public static String getString(Map<String, Object> map, String key, String defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    public static int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return defaultValue; }
    }

    // ==================== Shell 命令拆分 ====================

    /**
     * Shell 风格命令拆分，支持单引号和双引号包裹的参数
     * 例: java -jar app.jar --spring.profiles.active="prod dev" -> [java, -jar, app.jar, --spring.profiles.active=prod dev]
     */
    public static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'') {
                    inSingleQuote = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    // ==================== YAML 生成辅助 ====================

    @SuppressWarnings("unchecked")
    public static void appendEnvironment(StringBuilder sb, Map<String, Object> config) {
        if (config == null || !config.containsKey("envVars")) return;
        Map<String, String> envVars = (Map<String, String>) config.get("envVars");
        if (envVars == null || envVars.isEmpty()) return;
        sb.append("    environment:\n");
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            sb.append("      - ").append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    public static void appendVolumes(StringBuilder sb, Map<String, Object> config) {
        if (config == null || !config.containsKey("dataMount")) return;
        Map<String, Object> dataMount = (Map<String, Object>) config.get("dataMount");
        if (dataMount == null) return;
        String containerPath = getString(dataMount, "containerPath", "");
        if (containerPath.isEmpty()) return;
        String hostDir = getString(dataMount, "hostDir", "./data");
        sb.append("    volumes:\n");
        sb.append("      - ").append(hostDir).append(":").append(containerPath).append("\n");
    }

    // ==================== PortMapping 工具方法 ====================

    public static PortMapping getPrimaryMapping(List<PortMapping> mappings) {
        for (PortMapping pm : mappings) {
            if (pm.isPrimary()) return pm;
        }
        return null;
    }

    public static List<PortMapping> getMappingsByTarget(List<PortMapping> mappings, String target) {
        List<PortMapping> result = new ArrayList<>();
        for (PortMapping pm : mappings) {
            String pmTarget = pm.getTarget() != null ? pm.getTarget() : "backend";
            if (pmTarget.equals(target != null ? target : "backend")) {
                result.add(pm);
            }
        }
        return result;
    }

    public static List<PortMapping> getExposeMappings(List<PortMapping> mappings) {
        List<PortMapping> result = new ArrayList<>();
        for (PortMapping pm : mappings) {
            if (pm.isExpose()) result.add(pm);
        }
        return result;
    }

    public static List<PortMapping> getHostPortMappings(List<PortMapping> mappings) {
        List<PortMapping> result = new ArrayList<>();
        for (PortMapping pm : mappings) {
            if (!pm.isExpose() && pm.getHostPort() != null) result.add(pm);
        }
        return result;
    }
}
