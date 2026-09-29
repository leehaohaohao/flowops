package com.nexa.flowops.service.nodepackage;

/**
 * 分发记录状态（契约 §5.3）：{@code PENDING → UPLOADING → VERIFYING → SUCCEEDED/FAILED}。
 *
 * <p>只有 {@link #isTerminal()} 为真的状态是终态；v1 不提供取消、暂停与字节级进度。
 */
public enum PackageDistributionStatus {

    PENDING,
    UPLOADING,
    VERIFYING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }

    public boolean isInFlight() {
        return !isTerminal();
    }

    public static boolean isTerminal(String name) {
        return SUCCEEDED.name().equals(name) || FAILED.name().equals(name);
    }
}
