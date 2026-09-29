package com.nexa.flowops.service.nodepackage;

/**
 * 分发特有的失败错误码（阶段 2 P3，契约 {@code docs/frontend-api/node-package-api.md} §5.4）。
 *
 * <p>连接/认证/私钥类失败不在这里：它们直接复用阶段 1 的
 * {@link com.nexa.flowops.service.node.SshTestResultCode} 名称与中文说明。
 * 本枚举只覆盖 SFTP 传输与远端命令阶段的失败。
 */
public enum PackageDistributionErrorCode {

    REMOTE_DIR_MISSING("目标机固定目录不存在（需运维预置 /opt/flowops/runner/packages）"),
    REMOTE_DIR_NOT_WRITABLE("目标机目录当前账号不可写"),
    REMOTE_DISK_INSUFFICIENT("目标机可用空间不足"),
    REMOTE_WRITE_FAILED("写入远端临时文件失败"),
    REMOTE_TOOL_MISSING("目标机缺少基础命令（sha256sum / mv）"),
    REMOTE_COMMAND_TIMEOUT("远端校验或改名命令超时"),
    REMOTE_COMMAND_FAILED("远端校验或改名命令失败"),
    REMOTE_CHECKSUM_MISMATCH("远端临时文件摘要与包摘要不一致，未改名为正式包"),
    SSH_CONFIG_CHANGED("SSH 设置已变更或已被删除，需重新配置并测试连接后再分发"),
    SSH_NOT_VERIFIED("该节点 SSH 最近一次测试未通过，请重新测试连接后再分发"),
    STORE_CHECKSUM_MISMATCH("主节点存储的发布包与摘要不一致，请重新上传该包"),
    UPLOAD_TIMEOUT("传输超时"),
    UPLOAD_INTERRUPTED("传输中断（SFTP 会话断开）"),
    MASTER_RESTARTED("主节点在分发过程中重启"),
    STORE_READ_FAILED("主节点读取已存储发布包失败"),
    INTERNAL_ERROR("未预期错误");

    private final String message;

    PackageDistributionErrorCode(String message) {
        this.message = message;
    }

    /** 固定中文说明，可直接返回前端 */
    public String message() {
        return message;
    }
}
