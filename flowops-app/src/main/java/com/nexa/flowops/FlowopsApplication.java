package com.nexa.flowops;

import com.nexa.protocol.master.autoconfigure.EnableNexaMaster;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
@EnableNexaMaster
public class FlowopsApplication {

	public static void main(String[] args) {
		loadDotenv();
		SpringApplication.run(FlowopsApplication.class, args);
	}

	private static void loadDotenv() {
		String profile = System.getProperty("spring.profiles.active",
				System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "prod"));
		String envFile = ".env." + profile;

		File file = new File(envFile);
		if (!file.exists()) {
			System.out.println("[Dotenv] 未找到 " + envFile + "，工作目录: " + System.getProperty("user.dir"));
			return;
		}

		try {
			Dotenv dotenv = Dotenv.configure()
					.directory(".")
					.filename(envFile)
					.load();
			dotenv.entries().forEach(entry -> {
				if (System.getProperty(entry.getKey()) == null) {
					System.setProperty(entry.getKey(), entry.getValue());
				}
			});
			System.out.println("[Dotenv] 已加载 " + envFile + " 中的 " + dotenv.entries().size() + " 个变量");
		} catch (Exception e) {
			System.err.println("[Dotenv] 加载 " + envFile + " 失败: " + e.getMessage());
		}
	}
}
