package com.nexa.flowops.service.nodepackage;

import com.nexa.flowops.common.base.BusinessException;

/**
 * 分发请求级失败（阶段 2 P3，契约 §4）：在创建记录之前就能判定的问题，
 * 经 {@code GlobalExceptionHandler} 原样返回 {@code Result}（400/404/409）。
 *
 * <p>运行期失败（连接、传输、远端命令）不走这里，它们是业务结果，
 * 用 {@link PackageDistributionFailure} 记录成 {@code FAILED} + {@code errorCode}。
 */
public class PackageDistributionRequestException extends BusinessException {

    public static final int CODE_INVALID = 400;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_CONFLICT = 409;

    private PackageDistributionRequestException(int code, String message) {
        super(code, message);
    }

    public static PackageDistributionRequestException nodeNotRegistered(String runnerId) {
        return new PackageDistributionRequestException(CODE_NOT_FOUND, "节点未登记: " + runnerId);
    }

    public static PackageDistributionRequestException recordNotFound(Long id) {
        return new PackageDistributionRequestException(CODE_NOT_FOUND, "分发记录不存在: " + id);
    }

    public static PackageDistributionRequestException sshNotConfigured() {
        return new PackageDistributionRequestException(CODE_INVALID, "尚未配置 SSH 目标");
    }

    public static PackageDistributionRequestException algorithmRequired() {
        return new PackageDistributionRequestException(CODE_INVALID, "尚未选择主机密钥算法，请先补全 SSH 设置");
    }

    public static PackageDistributionRequestException sshNotVerified() {
        return new PackageDistributionRequestException(CODE_INVALID, "该节点 SSH 尚未通过连接测试，请先测试连接");
    }

    public static PackageDistributionRequestException inProgress() {
        return new PackageDistributionRequestException(CODE_CONFLICT, "该节点已有分发任务进行中");
    }

    public static PackageDistributionRequestException queueFull() {
        return new PackageDistributionRequestException(CODE_CONFLICT, "分发队列已满，请稍后重试");
    }
}
