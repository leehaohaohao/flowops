package com.nexa.flowops.dto;

import lombok.Data;

/**
 * 保存节点 SSH 目标的请求（PUT /api/nodes/registry/{runnerId}/ssh）。
 *
 * <p>全量字段必填：不做"只改一个字段"的局部更新，避免前端漏传导致目标被置空。
 * 私钥不在请求中传递，只传管理员预先放好的别名。
 */
@Data
public class SshTargetRequest {

    /** 宿主机地址，不含端口与 scheme */
    private String host;
    /** SSH 端口，缺省 22 */
    private Integer port;
    /** SSH 用户名 */
    private String username;
    /** 私钥别名，对应容器内 key-dir/&lt;keyAlias&gt; */
    private String keyAlias;
    /** 远端主机密钥指纹，接受带/不带 SHA256: 前缀与 = 填充 */
    private String hostKeySha256;
    /** 主机密钥算法（ED25519/ECDSA/RSA），必填；指纹必须取自该算法的目标机主机公钥（H1.2） */
    private String hostKeyAlgorithm;
}
