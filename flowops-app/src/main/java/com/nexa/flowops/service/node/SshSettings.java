package com.nexa.flowops.service.node;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SSH 连接验证的可配置项（S1 契约中的超时与密钥目录）。
 *
 * <p>默认值与 docs/2026-09-27-node-ssh-connection-plan.md 的契约表一致；未在 application.yml 中配置时按此生效。
 * 连接、认证、只读命令、SFTP 各有独立且有界的超时，避免探测请求长时间挂住线程。
 */
@Data
@Component
@ConfigurationProperties(prefix = "flowops.ssh")
public class SshSettings {

    /** 容器内私钥目录；数据库中只保存别名 */
    private String keyDir = "/data/flowops/ssh-keys";

    /** TCP 建立 + SSH 握手（含主机密钥校验）超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 公钥认证超时 */
    private Duration authTimeout = Duration.ofSeconds(5);

    /** 只读命令（true）执行超时 */
    private Duration commandTimeout = Duration.ofSeconds(5);

    /** SFTP 通道打开超时 */
    private Duration sftpTimeout = Duration.ofSeconds(5);
}
