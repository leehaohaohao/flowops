package com.nexa.flowops.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成"启动配置来源"报告：每个关键配置项最终取值来自哪个属性源。
 *
 * <p>每行给出四列：<b>定义来源</b>（键写在哪）、<b>占位符填充来源</b>（{@code ${DB_URL}} 这类占位符由谁填的）、
 * <b>生效值</b>（敏感项一律掩码）、以及键名本身。用于回答"这个参数到底是从哪儿生效的"。
 *
 * <p>敏感项（口令、密钥）只报告来源，值不打印；值里内联的 {@code password=}/{@code secret=} 也会被抹掉。
 * 解析顺序见 docs/configuration-loading-order.md，本类只做展示，不改变任何解析行为。
 */
public final class ConfigSourceReporter {

    /** 属性源顺序说明，须与 docs/configuration-loading-order.md 保持一致。 */
    static final String ORDER_HINT =
            "解析顺序（高→低）：命令行参数 > -D 系统属性 > 环境变量(-e/--env-file) > .env.<profile> > application-<profile>.yml > application.yml";

    /** 报告开关，置 false 可关闭。 */
    static final String ENABLED_KEY = "flowops.config-report.enabled";

    private static final String MASKED = "********（敏感，值已隐藏）";
    private static final String ABSENT = "<未配置>";
    private static final String NONE = "—";
    private static final String NOT_DEFINED = "无（未在任何来源中定义）";

    private static final int KEY_WIDTH = 36;
    private static final int ORIGIN_WIDTH = 30;
    private static final int FILL_WIDTH = 34;

    /** 需要报告的关键配置项：键 → 是否敏感。LinkedHashMap 保证输出顺序稳定。 */
    private static final Map<String, Boolean> WATCHED = new LinkedHashMap<>();

    static {
        WATCHED.put("spring.profiles.active", false);
        WATCHED.put("server.port", false);
        WATCHED.put("spring.datasource.url", false);
        WATCHED.put("spring.datasource.username", false);
        WATCHED.put("spring.datasource.password", true);
        WATCHED.put("spring.datasource.driver-class-name", false);
        WATCHED.put("sa-token.token-name", false);
        WATCHED.put("sa-token.jwt-secret-key", true);
        WATCHED.put("nexa.master.enabled", false);
        WATCHED.put("nexa.master.host", false);
        WATCHED.put("nexa.master.port", false);
        WATCHED.put("nexa.master.heartbeat-timeout", false);
        WATCHED.put("docker.host", false);
        WATCHED.put("app.storage.path", false);
        WATCHED.put("app.logs.path", false);
    }

    /** 值里内联的敏感片段，例如 jdbc:mysql://host/db?user=x&password=y。 */
    private static final Pattern SECRET_IN_VALUE =
            Pattern.compile("(?i)\\b(password|pwd|secret|token)\\s*=\\s*([^&;\\s]+)");

    /** 占位符 ${NAME} 或 ${NAME:default}。 */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([^:{}]+)(?::([^{}]*))?}");

    private static final Pattern UNRESOLVED_PLACEHOLDER =
            Pattern.compile("Could not resolve placeholder '([^']+)'");

    private ConfigSourceReporter() {
    }

    /** 是否启用报告，默认启用。 */
    public static boolean isEnabled(Environment environment) {
        return environment.getProperty(ENABLED_KEY, Boolean.class, Boolean.TRUE);
    }

    /** 生成多行报告文本；调用方整块写日志，避免逐行前缀破坏表格对齐。 */
    public static String report(Environment environment) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("\n==================== 配置来源（启动解析） ====================");
        sb.append("\n").append(ORDER_HINT);
        sb.append("\n  ")
          .append(pad("键", KEY_WIDTH)).append("  ")
          .append(pad("定义来源", ORIGIN_WIDTH)).append("  ")
          .append(pad("占位符填充来源", FILL_WIDTH)).append("  ")
          .append("生效值");

        for (Map.Entry<String, Boolean> entry : WATCHED.entrySet()) {
            String key = entry.getKey();
            boolean sensitive = entry.getValue();
            PropertySource<?> origin = originSource(environment, key);

            sb.append("\n  ")
              .append(pad(key, KEY_WIDTH)).append("  ")
              .append(pad(origin == null ? NOT_DEFINED : describeSource(origin), ORIGIN_WIDTH)).append("  ")
              .append(pad(fillSource(environment, origin, key), FILL_WIDTH)).append("  ")
              .append(valueOf(environment, key, sensitive));
        }
        sb.append("\n============================================================");
        return sb.toString();
    }

    /** 查找某个键第一个命中的属性源（即真正定义它的来源）。 */
    static PropertySource<?> originSource(Environment environment, String key) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return null;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            // Spring Boot 把所有来源包装进 configurationProperties，它命中一切键，需跳过
            if (isWrapper(source.getName())) {
                continue;
            }
            if (source.getProperty(key) != null) {
                return source;
            }
        }
        return null;
    }

    /** 某个键的定义来源名称；未定义时返回提示文案。 */
    static String originOf(Environment environment, String key) {
        PropertySource<?> origin = originSource(environment, key);
        return origin == null ? NOT_DEFINED : describeSource(origin);
    }

    /**
     * 占位符填充来源：如 {@code application-local.yml} 里写的是 {@code ${DB_URL}}，
     * 则这里给出真正提供值的来源，例如 {@code 环境变量(-e/--env-file)[DB_URL]}；
     * 走占位符默认值时给出 {@code 默认值(8081)}；没有占位符则为 {@value #NONE}。
     */
    static String fillSource(Environment environment, PropertySource<?> origin, String key) {
        if (origin == null) {
            return NONE;
        }
        String raw = String.valueOf(origin.getProperty(key));
        Matcher matcher = PLACEHOLDER.matcher(raw);
        Set<String> parts = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String defaultValue = matcher.group(2);
            PropertySource<?> filler = originSource(environment, name);
            if (filler != null) {
                parts.add(describeSource(filler) + "[" + name + "]");
            } else if (defaultValue != null) {
                parts.add("默认值(" + defaultValue + ")");
            } else {
                parts.add(name + " 未注入");
            }
        }
        return parts.isEmpty() ? NONE : String.join(" + ", parts);
    }

    /** 已解析的生效值；敏感项一律掩码，但"占位符未注入"这类缺口由填充来源列体现。 */
    static String valueOf(Environment environment, String key, boolean sensitive) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (RuntimeException e) {
            String missing = unresolvedPlaceholder(e);
            if (missing != null) {
                return sensitive ? MASKED : "<未解析：占位符 ${" + missing + "} 未注入>";
            }
            return sensitive ? MASKED : "解析失败：" + e.getMessage();
        }
        if (sensitive) {
            return MASKED;
        }
        if (value == null) {
            return ABSENT;
        }
        return SECRET_IN_VALUE.matcher(value).replaceAll("$1=********");
    }

    private static String unresolvedPlaceholder(RuntimeException e) {
        Matcher matcher = UNRESOLVED_PLACEHOLDER.matcher(String.valueOf(e.getMessage()));
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 把属性源名字规范化成运维看得懂的短名。 */
    static String describeSource(PropertySource<?> source) {
        String name = source.getName();
        return switch (name) {
            case "commandLineArgs" -> "命令行参数";
            case "systemProperties" -> "-D 系统属性";
            case "systemEnvironment" -> "环境变量(-e/--env-file)";
            case "random" -> "随机值";
            case "defaultProperties" -> "默认属性(代码)";
            default -> {
                if (name.startsWith("dotenv[")) {
                    yield name.substring("dotenv[".length(), name.length() - 1);
                }
                // Config Data 源形如：
                // Config resource 'class path resource [application-prod.yml]' via location 'optional:classpath:/'
                int open = name.indexOf('[');
                int close = name.indexOf(']', open + 1);
                if (open >= 0 && close > open + 1) {
                    yield name.substring(open + 1, close);
                }
                yield name;
            }
        };
    }

    private static boolean isWrapper(String sourceName) {
        return sourceName.contains("configurationProperties");
    }

    /**
     * 按显示宽度补空格：中日韩字符在终端占两列，若按 {@code String.length()} 对齐，
     * 含中文的行会整体错位。
     */
    private static String pad(String text, int width) {
        int display = displayWidth(text);
        if (display >= width) {
            return text;
        }
        return text + " ".repeat(width - display);
    }

    static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            width += isWide(codePoint) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || codePoint >= 0x20000;
    }
}
