package com.nexa.flowops.service.nodepackage;

import com.nexa.flowops.common.base.BusinessException;

/**
 * 发布包异常：经 {@code GlobalExceptionHandler} 原样返回 {@code Result}，
 * 前端据 {@code code} 与 {@code msg} 区分"参数/内容不合法、未找到、存储不可用"。
 *
 * <p>只携带固定原因，不携带包内容、异常栈或服务器绝对路径。
 */
public class RunnerPackageException extends BusinessException {

    private final RunnerPackageFailure failure;

    public RunnerPackageException(RunnerPackageFailure failure, String detail) {
        super(failure.code(), failure.messageWith(detail));
        this.failure = failure;
    }

    public RunnerPackageException(RunnerPackageFailure failure) {
        this(failure, null);
    }

    public RunnerPackageFailure failure() {
        return failure;
    }
}
