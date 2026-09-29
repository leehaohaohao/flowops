package com.nexa.flowops.service.nodepackage;

import com.nexa.flowops.service.node.SshTestResultCode;

/**
 * 分发运行期失败（阶段 2 P3，契约 §5.4）：由后台任务捕获后写入
 * {@code FAILED} + {@code error_code}/{@code error_message}，**不会**变成 HTTP 500。
 *
 * <p>错误码来源两类：阶段 1 的 {@link SshTestResultCode}（连接/认证/私钥）与
 * {@link PackageDistributionErrorCode}（SFTP 传输与远端命令）。
 */
public class PackageDistributionFailure extends RuntimeException {

    private final String errorCode;
    private final String message;

    public PackageDistributionFailure(String errorCode, String message) {
        super(errorCode);
        this.errorCode = errorCode;
        this.message = message;
    }

    public PackageDistributionFailure(SshTestResultCode code) {
        this(code.name(), code.message());
    }

    public PackageDistributionFailure(PackageDistributionErrorCode code) {
        this(code.name(), code.message());
    }

    public String errorCode() {
        return errorCode;
    }

    @Override
    public String getMessage() {
        return message;
    }

    /** 固定中文说明（与 {@link #getMessage()} 相同，供服务层写库） */
    public String errorMessage() {
        return message;
    }
}
