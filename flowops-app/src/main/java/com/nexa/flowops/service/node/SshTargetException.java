package com.nexa.flowops.service.node;

import com.nexa.flowops.common.base.BusinessException;

/**
 * SSH 目标配置异常：经 GlobalExceptionHandler 原样返回 Result，前端据此区分
 * "参数不合法 / 未配置 / 节点未登记"。
 *
 * <p>连接测试本身失败<strong>不走异常</strong>：那是业务结果，用 {@link SshTestResultCode} 表达。
 */
public class SshTargetException extends BusinessException {

    /** 参数不合法，或尚未配置 SSH 目标 */
    public static final int CODE_INVALID = 400;
    /** 节点未登记（nexa_node 无该 runnerId） */
    public static final int CODE_NOT_REGISTERED = 404;

    public SshTargetException(int code, String message) {
        super(code, message);
    }

    public static SshTargetException invalid(String message) {
        return new SshTargetException(CODE_INVALID, message);
    }

    public static SshTargetException notConfigured() {
        return new SshTargetException(CODE_INVALID, "尚未配置 SSH 目标");
    }

    public static SshTargetException notRegistered(String runnerId) {
        return new SshTargetException(CODE_NOT_REGISTERED, "节点未登记: " + runnerId);
    }
}
