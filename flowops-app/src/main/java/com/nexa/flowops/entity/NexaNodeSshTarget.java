package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 子节点宿主机的 SSH 目标（阶段 1：主节点到子节点宿主机的 SSH 连接验证）。
 *
 * <p>身份沿用 {@code nexa_node.runner_id}：本表一行对应一个已登记节点。
 * 只保存私钥<strong>别名</strong>，私钥文件由管理员放在主节点容器的
 * {@code <flowops.ssh.key-dir>/<keyAlias>}，数据库与 API 都不保存私钥内容。
 *
 * <p>本表与 Nexa Protocol 的子节点连接是两条独立链路：不参与注册 token 校验，也不代表节点在线。
 */
@Data
@TableName("nexa_node_ssh_target")
public class NexaNodeSshTarget {

    /** 节点 ID，对应 nexa_node.runner_id */
    @TableId(type = IdType.INPUT)
    private String runnerId;
    /** 宿主机地址（不含端口与 scheme） */
    private String host;
    /** SSH 端口，默认 22 */
    private Integer port;
    /** SSH 用户名 */
    private String username;
    /** 私钥别名；容器内实际路径为 key-dir/keyAlias */
    private String keyAlias;
    /** 远端主机密钥指纹，归一化为 SHA256:<43 字符无填充 base64> */
    private String hostKeySha256;
    /** 主机密钥算法：ED25519/ECDSA/RSA，与指纹成对；NULL=旧记录待补齐（H1.2） */
    private String hostKeyAlgorithm;
    /** SSH 设置版本：PUT 覆盖设置时递增；测试结果只有版本一致时才能写回 */
    private Long configVersion;
    /** 最近一次连接测试结果码，见 SshTestResultCode */
    private String lastResultCode;
    /** 最近一次连接测试结果说明 */
    private String lastResultMessage;
    /** 最近一次连接测试时间 */
    private LocalDateTime lastTestedAt;
    /** 最近一次连接测试耗时（毫秒） */
    private Long lastDurationMs;
    /** 最新一次测试的序号：测试开始时递增登记，只有最新序号能写回结果 */
    private Long latestTestSeq;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
