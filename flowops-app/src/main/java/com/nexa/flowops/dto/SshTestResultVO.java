package com.nexa.flowops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次 SSH 连接测试的结果（POST /api/nodes/registry/{runnerId}/ssh/test 的 data，也是 GET 响应中的 lastTest）。
 *
 * <p>接口调用成功即 code=200，连接是否成功由 {@link #resultCode} 表达：
 * 便于前端区分"接口失败"与"SSH 失败"。message 为固定中文说明，不含远端输出与异常栈。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SshTestResultVO {

    /** 结果码，见 SshTestResultCode */
    private String resultCode;
    /** 结果说明（固定中文文案） */
    private String message;
    /** 测试时间 */
    private LocalDateTime testedAt;
    /** 耗时（毫秒） */
    private Long durationMs;
}
