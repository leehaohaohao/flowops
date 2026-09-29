package com.nexa.flowops.service.nodepackage;

/**
 * 发布包上传/查询的失败原因（阶段 2 P3）。
 *
 * <p>枚举名是**服务端内部原因**（用于日志、测试断言与实现内部分支），对外只返回
 * {@link #message()} 固定中文文案，不把原因码加进 {@code Result} 信封（契约决策 D12）。
 * 上传失败固定 {@code code=400}，其余按 {@link #code()} 返回。
 */
public enum RunnerPackageFailure {

    PACKAGE_EMPTY(400, "发布包文件不能为空"),
    PACKAGE_TOO_LARGE(400, "发布包超过 1 GiB 上限"),
    PACKAGE_NAME_MISMATCH(400, "发布包文件名应为 flowops-executor-<版本>-linux-amd64.tar.gz"),
    PACKAGE_NAME_VERSION_MISMATCH(400, "发布包文件名与 manifest 版本不一致"),
    PACKAGE_PLATFORM_UNSUPPORTED(400, "发布包平台不受支持（仅 linux/amd64）"),
    MANIFEST_FORMAT_UNSUPPORTED(400, "发布包格式版本不受支持"),
    MANIFEST_INVALID(400, "发布包 manifest 不合法"),
    ARCHIVE_INVALID(400, "发布包归档格式不合法"),
    MEMBER_INVALID(400, "发布包成员不合法"),
    MEMBER_TOO_LARGE(400, "发布包成员超出大小上限"),
    CHECKSUMS_INVALID(400, "发布包校验清单不合法"),
    CHECKSUM_MISMATCH(400, "发布包成员摘要不一致"),
    PACKAGE_NOT_FOUND(404, "发布包不存在"),
    SHA256_INVALID(400, "sha256 格式非法"),
    STORE_UNAVAILABLE(500, "发布包存储不可用，请检查主节点磁盘与权限");

    private final int code;
    private final String message;

    RunnerPackageFailure(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 对外 HTTP 业务码（响应体 {@code code}） */
    public int code() {
        return code;
    }

    /** 固定中文说明，可直接返回前端 */
    public String message() {
        return message;
    }

    /** 追加成员/字段等定位信息后的说明，便于页面提示与排查 */
    public String messageWith(String detail) {
        return detail == null || detail.isBlank() ? message : message + ": " + detail;
    }
}
