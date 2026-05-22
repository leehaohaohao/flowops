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

public class DotenvPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DotenvPostProcessor.class);

    @Override
    public int getOrder() {
        // 系统属性/环境变量的 PostProcessor 在 HIGHEST_PRECEDENCE 先执行
        // ConfigDataEnvironmentPostProcessor（加载 yml）在 HIGHEST_PRECEDENCE + 10 执行
        // 我们夹在中间：系统环境变量 > .env > yml
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] profiles = environment.getActiveProfiles();
        String envFile = profiles.length > 0 ? ".env." + profiles[0] : ".env";

        if (!new File(envFile).exists()) {
            log.info("未找到 {}，跳过加载", envFile);
            return;
        }

        Dotenv dotenv = Dotenv.configure()
                .directory(".")
                .filename(envFile)
                .ignoreIfMissing()
                .load();

        Map<String, Object> props = new HashMap<>();
        dotenv.entries().forEach(entry -> props.put(entry.getKey(), entry.getValue()));

        // addLast: 此时系统属性/环境变量已加载，addLast 放在它们之后
        // 后续 yml 会被 ConfigDataEnvironmentPostProcessor 加在更后面
        environment.getPropertySources()
                .addLast(new MapPropertySource("dotenv-" + envFile, props));

        log.info("已加载 {} 中的 {} 个变量", envFile, props.size());
    }
}
