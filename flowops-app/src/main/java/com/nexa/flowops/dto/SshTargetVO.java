package com.nexa.flowops.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点 SSH 目标响应（脱敏）：不含私钥内容、不含服务器绝对路径、不含异常栈。
 */
@Data
public class SshTargetVO {

    private String runnerId;
    private String host;
    private Integer port;
    private String username;
    /** 私钥别名（不是路径） */
    private String keyAlias;
    /** 归一化后的主机密钥指纹 */
    private String hostKeySha256;
    /** 主机密钥算法（ED25519/ECDSA/RSA）；旧记录为 null，需补齐后才能测试（H1.2） */
    private String hostKeyAlgorithm;
    /** 容器内该别名的私钥是否存在且为普通文件；只表示"有/无"，不返回路径 */
    private boolean keyFileExists;
    /** 最近一次连接测试结果；从未测试时为 null */
    private SshTestResultVO lastTest;
    /** 设置最近一次变更时间 */
    private LocalDateTime updateTime;
}
