package com.nexa.flowops.service.nodepackage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 发布包接收与分发配置（阶段 2 P3，契约见 {@code docs/frontend-api/node-package-api.md} §7）。
 *
 * <p>默认值与契约表一致；未在 {@code application.yml} 中配置时按此生效（与 {@code SshSettings} 同一做法）。
 * SSH 连接/认证/SFTP 通道超时复用 {@code flowops.ssh.*}，此处只提供传输与远端命令的更长超时。
 */
@Data
@Component
@ConfigurationProperties(prefix = "flowops.runner-package")
public class RunnerPackageSettings {

    /** 主节点不可变存储目录（应用自行创建） */
    private String storeDir = "/data/flowops/runner-packages";

    /** 目标机固定目录（由 SSH 管理账号预置，应用不创建、不改权限） */
    private String remoteDir = "/opt/flowops/runner/packages";

    /** 压缩包大小上限（1 GiB，超出即拒绝） */
    private DataSize maxSize = DataSize.ofGigabytes(1);

    /** 单次传输整体超时 */
    private Duration uploadTimeout = Duration.ofMinutes(30);

    /** 远端单条命令（sha256sum/df/mv/rm）超时 */
    private Duration remoteCommandTimeout = Duration.ofMinutes(5);

    /** 远端空间预检余量：可用空间必须 ≥ 包大小 + 余量 */
    private DataSize remoteFreeSpaceMargin = DataSize.ofMegabytes(64);

    /** 全局并发分发上限（同一节点始终串行） */
    private int maxConcurrentDistributions = 2;

    /** 等待执行的分发队列容量；超出返回 409 */
    private int distributionQueueCapacity = 32;

    /** 结构上限：单个成员解压后大小（2 GiB） */
    private DataSize maxMemberSize = DataSize.ofGigabytes(2);

    /** 结构上限：全部成员解压后总计（4 GiB） */
    private DataSize maxTotalUncompressedSize = DataSize.ofGigabytes(4);

    /** 结构上限：归档成员数 */
    private int maxEntries = 64;
}
