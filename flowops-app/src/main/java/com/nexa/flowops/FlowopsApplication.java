package com.nexa.flowops;

import com.nexa.flowops.config.ConfigSourceReportListener;
import com.nexa.protocol.master.autoconfigure.EnableNexaMaster;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableNexaMaster
public class FlowopsApplication {

	public static void main(String[] args) {
		// dotenv 由 DotenvPostProcessor（EnvironmentPostProcessor）统一加载，
		// 它只把 .env.<profile> 注册为一个属性源，因此优先级低于命令行参数/-D/-e，高于 application*.yml。
		// 不要再在 main 里用 System.setProperty 注入，那会把文件值抬到环境变量之上，导致 -e 失效。
		SpringApplication application = new SpringApplication(FlowopsApplication.class);
		// 启动日志打印各关键配置项的来源（敏感项只显示来源），便于排查参数到底从哪儿生效
		application.addListeners(new ConfigSourceReportListener());
		application.run(args);
	}

}
