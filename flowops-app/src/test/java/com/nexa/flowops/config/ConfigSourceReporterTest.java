package com.nexa.flowops.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 校验启动配置来源报告：来源归因正确、敏感值不落日志、占位符缺口能显示出来。
 */
class ConfigSourceReporterTest {

    private static final String YML_SOURCE =
            "Config resource 'class path resource [application-prod.yml]' via location 'optional:classpath:/'";

    private StandardEnvironment env;

    @BeforeEach
    void setUp() {
        env = new StandardEnvironment();

        // Spring Boot 会附加一个 configurationProperties 包装源；它对任何键都能取到值，必须被跳过。
        // 这里只放一个"独占键"，避免影响其它键的取值（真实包装源是委托给其余来源的）。
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("wrapper.only.key", "wrapper-should-be-ignored");
        env.getPropertySources().addFirst(new MapPropertySource("configurationProperties", wrapper));

        Map<String, Object> args = new HashMap<>();
        args.put("server.port", "9999");
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", args));

        Map<String, Object> dotenv = new HashMap<>();
        dotenv.put("spring.datasource.password", "dotenv-secret");
        dotenv.put("spring.datasource.url", "jdbc:mysql://db:3306/flowops?user=root&password=url-secret");
        env.getPropertySources().addLast(new MapPropertySource("dotenv[.env.prod]", dotenv));

        Map<String, Object> yml = new HashMap<>();
        yml.put("app.storage.path", "/data/flowops/services");
        yml.put("nexa.master.port", "${FLOWOPS_TEST_MISSING_ENV:8081}");
        yml.put("docker.host", "${FLOWOPS_TEST_REQUIRED_ENV}");
        yml.put("sa-token.jwt-secret-key", "${FLOWOPS_TEST_MISSING_SECRET}");
        env.getPropertySources().addLast(new MapPropertySource(YML_SOURCE, yml));
    }

    @Test
    void attributesEachKeyToTheSourceThatWinsTheChain() {
        assertEquals("命令行参数", ConfigSourceReporter.originOf(env, "server.port"),
                "命令行参数应压过后面的所有来源");
        assertEquals(".env.prod", ConfigSourceReporter.originOf(env, "spring.datasource.password"));
        assertEquals("application-prod.yml", ConfigSourceReporter.originOf(env, "app.storage.path"));
        assertEquals("application-prod.yml", ConfigSourceReporter.originOf(env, "docker.host"));
        assertEquals("无（未在任何来源中定义）", ConfigSourceReporter.originOf(env, "wrapper.only.key"),
                "configurationProperties 包装源必须被跳过，否则每个键都会被归因到它");
        assertEquals("无（未在任何来源中定义）", ConfigSourceReporter.originOf(env, "not.defined.anywhere"));
    }

    @Test
    void reportsWhichSourceFillsEachPlaceholder() {
        assertEquals("默认值(8081)",
                ConfigSourceReporter.fillSource(env, ConfigSourceReporter.originSource(env, "nexa.master.port"), "nexa.master.port"),
                "占位符走默认值时，应显示是默认值而不是某个来源");
        assertEquals("FLOWOPS_TEST_REQUIRED_ENV 未注入",
                ConfigSourceReporter.fillSource(env, ConfigSourceReporter.originSource(env, "docker.host"), "docker.host"));
        assertEquals("—",
                ConfigSourceReporter.fillSource(env, ConfigSourceReporter.originSource(env, "server.port"), "server.port"),
                "不含占位符的键不需要填充来源");
        assertEquals("—", ConfigSourceReporter.fillSource(env, null, "not.defined.anywhere"));
    }

    @Test
    void sourceNamesAreNormalisedForOperators() {
        assertEquals("命令行参数", ConfigSourceReporter.describeSource(new MapPropertySource("commandLineArgs", Map.of())));
        assertEquals("-D 系统属性", ConfigSourceReporter.describeSource(new MapPropertySource("systemProperties", Map.of())));
        assertEquals("环境变量(-e/--env-file)", ConfigSourceReporter.describeSource(new MapPropertySource("systemEnvironment", Map.of())));
        assertEquals(".env.local", ConfigSourceReporter.describeSource(new MapPropertySource("dotenv[.env.local]", Map.of())));
        assertEquals("application-prod.yml", ConfigSourceReporter.describeSource(new MapPropertySource(YML_SOURCE, Map.of())));
    }

    @Test
    void neverPrintsSensitiveValuesButStillShowsTheirOrigin() {
        String report = ConfigSourceReporter.report(env);

        String passwordLine = lineFor(report, "spring.datasource.password");
        assertTrue(passwordLine.contains(".env.prod"), "敏感项仍要显示来源，实际: " + passwordLine);
        assertTrue(passwordLine.contains("********"), "敏感项应掩码，实际: " + passwordLine);
        assertFalse(report.contains("dotenv-secret"), "敏感值不得出现在日志里");
        assertFalse(report.contains("url-secret"), "值内联的口令片段也要抹掉");
        assertFalse(report.contains("wrapper-should-be-ignored"), "被跳过的包装源不得出现在报告里");

        String urlLine = lineFor(report, "spring.datasource.url");
        assertTrue(urlLine.contains("password=********"), "URL 内联口令应抹掉，实际: " + urlLine);
        assertTrue(urlLine.contains("jdbc:mysql://db:3306/flowops"), "非敏感部分保留");

        // 敏感项即使占位符没注入，也只暴露"缺口"，不暴露值
        String jwtLine = lineFor(report, "sa-token.jwt-secret-key");
        assertTrue(jwtLine.contains("********"), "敏感项应掩码，实际: " + jwtLine);
        assertTrue(jwtLine.contains("FLOWOPS_TEST_MISSING_SECRET 未注入"),
                "敏感项的占位符缺口要提示出来，实际: " + jwtLine);
        assertTrue(jwtLine.contains("application-prod.yml"), "来源仍要显示，实际: " + jwtLine);
    }

    @Test
    void showsPlaceholderDefaultsAndMissingVariables() {
        String report = ConfigSourceReporter.report(env);

        String portLine = lineFor(report, "nexa.master.port");
        assertTrue(portLine.contains("8081"), "占位符默认值应显示为生效值，实际: " + portLine);
        assertTrue(portLine.contains("application-prod.yml"), "定义来源应为写占位符的 yml，实际: " + portLine);
        assertTrue(portLine.contains("默认值(8081)"), "应说明值来自占位符默认值，实际: " + portLine);

        String requiredLine = lineFor(report, "docker.host");
        assertTrue(requiredLine.contains("FLOWOPS_TEST_REQUIRED_ENV 未注入"),
                "必填占位符未注入应直接显示出来，实际: " + requiredLine);
        assertTrue(requiredLine.contains("<未解析"), "无法解析时应显示 <未解析...>，实际: " + requiredLine);

        String absentLine = lineFor(report, "sa-token.token-name");
        assertTrue(absentLine.contains("<未配置>"), "未配置项应显示 <未配置>，实际: " + absentLine);
    }

    @Test
    void reportCanBeDisabled() {
        assertTrue(ConfigSourceReporter.isEnabled(env), "默认应启用");

        Map<String, Object> off = new HashMap<>();
        off.put(ConfigSourceReporter.ENABLED_KEY, "false");
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs2", off));
        assertFalse(ConfigSourceReporter.isEnabled(env));
    }

    @Test
    void alignsColumnsByDisplayWidthDespiteCjk() {
        String report = ConfigSourceReporter.report(env);

        // server.port 的填充来源列是纯 ASCII（—），nexa.master.port 的是含中文的「默认值(8081)」。
        // 两行的生效值（9999 / 8081）必须起始于同一显示列，否则按 String.length() 补齐就会错位。
        String serverPortLine = lineFor(report, "server.port");
        String nexaPortLine = lineFor(report, "nexa.master.port");
        int asciiFillValueColumn = ConfigSourceReporter.displayWidth(
                serverPortLine.substring(0, serverPortLine.lastIndexOf("9999")));
        int cjkFillValueColumn = ConfigSourceReporter.displayWidth(
                nexaPortLine.substring(0, nexaPortLine.lastIndexOf("8081")));

        assertEquals(asciiFillValueColumn, cjkFillValueColumn, "中文列宽导致生效值列错位");
    }

    private String lineFor(String report, String key) {
        for (String line : report.split("\n")) {
            if (line.contains(key)) {
                return line;
            }
        }
        return fail("报告中缺少键: " + key);
    }
}
