package com.nexa.flowops.service.network;

import com.nexa.flowops.common.base.BusinessException;

/**
 * 网络操作业务异常：携带可区分的错误码，经 GlobalExceptionHandler 原样返回 Result，
 * 前端据此区分无权限 / 未登记 / Docker 实体缺失 / 非 bridge / 重名 / 仍有引用 / 节点与网络不匹配。
 */
public class NetworkException extends BusinessException {

    /** 参数或规则不合法：网络名非法、非 bridge、节点与网络不匹配、内置网络 */
    public static final int CODE_INVALID = 400;
    /** 无权限（全局网络管理仅超级管理员；项目默认值仅超管或该项目主管） */
    public static final int CODE_FORBIDDEN = 403;
    /** 网络未登记（或对象不存在） */
    public static final int CODE_NOT_REGISTERED = 404;
    /** 已登记但主节点 Docker 实体缺失 */
    public static final int CODE_DOCKER_MISSING = 410;
    /** 冲突：重名、仍有引用（授权/默认值/服务）、Docker 容器占用 */
    public static final int CODE_CONFLICT = 409;
    /** Docker 命令执行失败或 Docker 不可用 */
    public static final int CODE_DOCKER_ERROR = 503;

    public NetworkException(int code, String message) {
        super(code, message);
    }

    public static NetworkException invalid(String message) {
        return new NetworkException(CODE_INVALID, message);
    }

    public static NetworkException forbidden(String message) {
        return new NetworkException(CODE_FORBIDDEN, message);
    }

    public static NetworkException notRegistered(String message) {
        return new NetworkException(CODE_NOT_REGISTERED, message);
    }

    public static NetworkException dockerMissing(String message) {
        return new NetworkException(CODE_DOCKER_MISSING, message);
    }

    public static NetworkException conflict(String message) {
        return new NetworkException(CODE_CONFLICT, message);
    }

    public static NetworkException dockerError(String message) {
        return new NetworkException(CODE_DOCKER_ERROR, message);
    }
}
