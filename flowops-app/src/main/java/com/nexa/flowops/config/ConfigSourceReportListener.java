package com.nexa.flowops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 在启动日志里打印配置来源表（见 {@link ConfigSourceReporter}）。
 *
 * <p>挂在 {@link ApplicationPreparedEvent}：此时 application*.yml 与 .env.&lt;profile&gt; 都已装载完毕，
 * 且尚未刷新上下文——即使后面数据库连接失败、应用启动中止，这张表也已经打印出来，
 * 正好用于排查"参数是从哪儿来的 / 必填变量是否注入"。
 *
 * <p>敏感项只显示来源，不显示值；可用 {@code flowops.config-report.enabled=false} 关闭。
 */
public class ConfigSourceReportListener implements ApplicationListener<ApplicationPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourceReportListener.class);

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        ConfigurableEnvironment environment = event.getApplicationContext().getEnvironment();
        if (!ConfigSourceReporter.isEnabled(environment)) {
            log.info("[配置来源] 报告已通过 {} = false 关闭", ConfigSourceReporter.ENABLED_KEY);
            return;
        }
        log.info(ConfigSourceReporter.report(environment));
    }
}
