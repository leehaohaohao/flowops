package com.nexa.flowops.service.node;

import java.util.Arrays;

/**
 * SSH 连接测试结果码（S1 定稿契约，见 docs/2026-09-27-node-ssh-connection-plan.md）。
 *
 * <p>接口只返回结果码与固定中文说明，不返回私钥、服务器路径、远端输出或异常栈。
 * 前端按结果码映射中文原因展示。
 */
public enum SshTestResultCode {

    CONNECTED("连接成功（握手、主机密钥、认证、只读命令与 SFTP 均通过）"),
    CONNECT_TIMEOUT("连接超时"),
    CONNECT_FAILED("无法连接（端口拒绝、地址不可达或域名解析失败）"),
    HOST_KEY_MISMATCH("主机密钥不匹配，已中止（疑似中间人）"),
    HOST_KEY_ALGORITHM_UNAVAILABLE("目标机未提供所选主机密钥算法"),
    HOST_KEY_ALGORITHM_REQUIRED("尚未选择主机密钥算法，旧记录需补齐后才能测试"),
    AUTH_TIMEOUT("认证超时"),
    AUTH_FAILED("公钥认证失败"),
    COMMAND_TIMEOUT("只读命令执行超时"),
    COMMAND_FAILED("只读命令失败（退出码非 0）"),
    SFTP_TIMEOUT("SFTP 通道打开超时"),
    SFTP_FAILED("SFTP 通道不可用"),
    KEY_NOT_FOUND("私钥文件不存在"),
    KEY_PERMISSION_TOO_OPEN("私钥文件权限过宽（要求 0600 或更严）"),
    KEY_ALIAS_INVALID("私钥别名非法"),
    KEY_UNREADABLE("私钥不可读（格式不支持或带口令）"),
    TEST_OBSOLETE("测试结果已过期（配置已变更或有更新的测试），未保存"),
    NODE_NOT_REGISTERED("节点未登记"),
    INTERNAL_ERROR("未预期错误");

    private final String message;

    SshTestResultCode(String message) {
        this.message = message;
    }

    /** 固定中文说明，可直接返回给前端。 */
    public String message() {
        return message;
    }

    /** 按名称解析，未知值返回 {@link #INTERNAL_ERROR}。 */
    public static SshTestResultCode fromName(String name) {
        if (name == null || name.isBlank()) {
            return INTERNAL_ERROR;
        }
        return Arrays.stream(values())
                .filter(code -> code.name().equals(name))
                .findFirst()
                .orElse(INTERNAL_ERROR);
    }
}
