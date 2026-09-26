package com.nexa.flowops.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 唯一的 dotenv 加载器。
 *
 * <p>把 {@code .env.<profile>}（找不到时降级为 {@code .env}）作为<strong>一个属性源</strong>追加到环境末尾，
 * 而不是写成 JVM 系统属性——这样它天然落在"命令行参数 / -D 系统属性 / 进程环境变量"之下，
 * 又落在 {@code application-<profile>.yml} / {@code application.yml} 之上，
 * 形成单条可逐变量降级的解析链（见 docs/configuration-loading-order.md）：
 *
 * <pre>
 *   1. 命令行参数          --key=value
 *   2. JVM 系统属性        -Dkey=value
 *   3. 进程环境变量        docker run -e / --env-file / shell export
 *   4. .env.&lt;profile&gt;     本类加载（找不到则 .env）
 *   5. application-&lt;profile&gt;.yml
 *   6. application.yml    以及代码内默认值
 * </pre>
 *
 * 每个变量独立按这条链查找，命中即止，因此不存在"某个键整体被某个来源盖掉"的情况。
 * 同一个键在上面的来源里没有时，会自动降级到下一个来源。
 */
public class DotenvPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DotenvPostProcessor.class);

    /** 未显式指定 profile 时的兜底值，与 Dockerfile 的默认值保持一致。 */
    static final String DEFAULT_PROFILE = "prod";

    @Override
    public int getOrder() {
        // 必须晚于 StandardEnvironment 装配 systemProperties / systemEnvironment（构造时即完成），
        // 这样 addLast 后 dotenv 仍低于系统属性与环境变量；
        // 又必须早于 ConfigDataEnvironmentPostProcessor（HIGHEST_PRECEDENCE + 10），
        // 这样 application*.yml 追加在 dotenv 之后，即 yml 优先级低于 dotenv。
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String profile = resolveProfile(environment);
        String envFile = firstExisting(".env." + profile, ".env");

        if (envFile == null) {
            log.info("[Dotenv] 未找到 .env.{} 与 .env（工作目录 {}），跳过；配置来自命令行参数/环境变量/application*.yml",
                    profile, System.getProperty("user.dir"));
            return;
        }

        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(".")
                    .filename(envFile)
                    .ignoreIfMissing()
                    .load();

            Map<String, Object> props = new HashMap<>();
            dotenv.entries().forEach(entry -> props.put(entry.getKey(), entry.getValue()));

            if (props.isEmpty()) {
                log.info("[Dotenv] {} 为空，跳过", envFile);
                return;
            }

            environment.getPropertySources()
                    .addLast(new MapPropertySource("dotenv[" + envFile + "]", props));

            log.info("[Dotenv] 已加载 {} 中的 {} 个变量（优先级：命令行参数 > -D 系统属性 > -e 环境变量 > 本文件 > application*.yml）",
                    envFile, props.size());
        } catch (Exception e) {
            log.error("[Dotenv] 加载 {} 失败，已跳过", envFile, e);
        }
    }

    /**
     * 解析当前 profile，取值方式由高到低与配置解析链一致：
     * 命令行参数（{@code --spring.profiles.active=x}）&gt; JVM 系统属性（{@code -Dspring.profiles.active=x}）
     * &gt; 进程环境变量（{@code SPRING_PROFILES_ACTIVE}）&gt; {@link #DEFAULT_PROFILE}。
     *
     * <p>本方法在 {@code ConfigDataEnvironmentPostProcessor} 之前执行，此时
     * {@code environment.getActiveProfiles()} 仍为空，因此不能依赖它。
     */
    private String resolveProfile(ConfigurableEnvironment environment) {
        // 该属性此刻能看到的来源就是命令行参数、-D 系统属性、进程环境变量三者（yml 尚未加载）
        String profile = environment.getProperty("spring.profiles.active");
        if (isBlank(profile)) {
            profile = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        if (isBlank(profile)) {
            return DEFAULT_PROFILE;
        }
        // 多 profile 形如 "prod,metrics"，取第一个作为 dotenv 文件后缀
        return profile.split(",")[0].trim();
    }

    /** 返回第一个存在的候选文件；都不存在时返回 {@code null}。 */
    private String firstExisting(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && new File(candidate).isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
