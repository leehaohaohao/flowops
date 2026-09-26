package com.nexa.flowops.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死 docs/configuration-loading-order.md 里的解析链：
 * 命令行参数 &gt; -D 系统属性 &gt; 进程环境变量 &gt; .env.&lt;profile&gt; &gt; application-&lt;profile&gt;.yml。
 *
 * <p>进程环境变量无法在测试进程内设置，因此对它不做取值断言，而是断言属性源顺序
 * （dotenv 源必须排在 systemEnvironment 之后），这与 Spring 的查找顺序等价。
 */
class DotenvLoadingOrderTest {

    private static final String PROFILE = "itprobe";
    private static final String MISSING_PROFILE = "itprobe-missing";
    private static final Path ENV_FILE = Path.of(".env." + PROFILE);

    @AfterEach
    void cleanup() throws IOException {
        Files.deleteIfExists(ENV_FILE);
        System.clearProperty("from.sysprop");
        System.clearProperty("spring.profiles.active");
    }

    @Test
    void resolvesEachVariableDownTheChainAndKeepsDotenvBelowEnvVars() throws IOException {
        // .env.itprobe：与 application-itprobe.yml 同名键 from.dotenv 用于验证文件高于 yml
        Files.writeString(ENV_FILE, String.join("\n",
                "# 测试用 dotenv 文件",
                "from.dotenv=dotenv-value",
                "from.arg=dotenv-value",
                "from.sysprop=dotenv-value",
                "QUOTED_KEY=\"quoted value\"") + "\n");

        System.setProperty("from.sysprop", "sysprop-value");

        try (ConfigurableApplicationContext ctx = start(PROFILE, "--from.arg=arg-value")) {
            ConfigurableEnvironment env = ctx.getEnvironment();

            // 逐变量降级：每个键各自命中链上最高优先级的来源
            assertEquals("dotenv-value", env.getProperty("from.dotenv"), "dotenv 应覆盖 application-itprobe.yml");
            assertEquals("yml-value", env.getProperty("from.yml"), "仅 yml 有的键应降级命中 yml");
            assertEquals("arg-value", env.getProperty("from.arg"), "命令行参数应覆盖 dotenv");
            assertEquals("sysprop-value", env.getProperty("from.sysprop"), "-D 系统属性应覆盖 dotenv");
            assertEquals("quoted value", env.getProperty("QUOTED_KEY"), "带引号的值应去引号");
            assertTrue(ctx.getEnvironment().getActiveProfiles().length > 0, "profile 应生效");

            assertSourceOrder(env, "dotenv[" + ENV_FILE + "]");
            assertEquals(1, countContaining(env.getPropertySources(), "dotenv["),
                    "dotenv 属性源只应出现一次（重复注册会导致同一文件被加载两次）");
        }
    }

    @Test
    void usesCommandLineProfileToPickTheEnvFile() {
        // 回归：--spring.profiles.active=... 这类"程序参数"过去被 loadDotenv 忽略，导致始终退回 prod 并加载 .env.prod。
        // 正面证据在 resolvesEachVariableDownTheChainAndKeepsDotenvBelowEnvVars（它只可能加载 .env.itprobe）；
        // 这里验证降级行为：profile 文件缺失时不报错、不误用 prod 的文件、yml 取值不受影响。
        try (ConfigurableApplicationContext ctx = start(MISSING_PROFILE)) {
            ConfigurableEnvironment env = ctx.getEnvironment();
            assertEquals(MISSING_PROFILE, env.getActiveProfiles()[0]);
            assertEquals("8080", env.getProperty("server.port"),
                    "profile 文件缺失时应静默跳过，基础 application.yml 取值不受影响");
            assertNull(env.getProperty("from.dotenv"), "缺失的 profile 文件不应贡献任何键");

            MutablePropertySources sources = env.getPropertySources();
            assertEquals(0, countContaining(sources, "dotenv[.env." + MISSING_PROFILE + "]"),
                    "不存在的 profile 文件不应产生属性源");
            assertEquals(0, countContaining(sources, "dotenv[.env.prod]"),
                    "程序参数指定的 profile 不应被忽略而退回 prod 的文件");
        }
    }

    private ConfigurableApplicationContext start(String profile, String... extraArgs) {
        List<String> args = new ArrayList<>();
        args.add("--spring.profiles.active=" + profile);
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(ProbeConfig.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args.toArray(String[]::new));
    }

    /**
     * 断言属性源相对顺序：systemProperties 高于 systemEnvironment，两者都高于 dotenv，
     * dotenv 高于 profile 专属 yml（即 env 变量能覆盖文件、文件能覆盖 yml）。
     */
    private void assertSourceOrder(ConfigurableEnvironment env, String dotenvSourceName) {
        MutablePropertySources sources = env.getPropertySources();
        int argv = indexOf(sources, "commandLineArgs");
        int sysProps = indexOf(sources, "systemProperties");
        int sysEnv = indexOf(sources, "systemEnvironment");
        int dotenv = indexOf(sources, dotenvSourceName);
        int profileYml = indexOfContaining(sources, "application-" + PROFILE + ".yml");

        assertTrue(argv >= 0, "commandLineArgs 属性源应存在");
        assertTrue(dotenv >= 0, "dotenv 属性源应存在");
        assertTrue(profileYml >= 0, "application-" + PROFILE + ".yml 属性源应存在");

        assertTrue(argv < sysProps, "命令行参数应高于 -D 系统属性");
        assertTrue(sysProps < sysEnv, "-D 系统属性应高于进程环境变量");
        assertTrue(sysEnv < dotenv, "进程环境变量应高于 .env 文件");
        assertTrue(dotenv < profileYml, ".env 文件应高于 application-<profile>.yml");
    }

    private int indexOf(MutablePropertySources sources, String name) {
        int index = 0;
        for (PropertySource<?> source : sources) {
            if (name.equals(source.getName())) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private int indexOfContaining(MutablePropertySources sources, String fragment) {
        int index = 0;
        for (PropertySource<?> source : sources) {
            if (source.getName().contains(fragment)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private int countContaining(MutablePropertySources sources, String fragment) {
        int count = 0;
        for (PropertySource<?> source : sources) {
            if (source.getName().contains(fragment)) {
                count++;
            }
        }
        return count;
    }

    /** 空配置类：只需要一个可启动的上下文，属性源顺序与配置类内容无关。 */
    @Configuration(proxyBeanMethods = false)
    static class ProbeConfig {
    }
}
