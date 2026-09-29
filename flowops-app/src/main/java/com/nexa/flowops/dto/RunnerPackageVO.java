package com.nexa.flowops.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已存储发布包的响应（阶段 2 P3，契约 {@code docs/frontend-api/node-package-api.md} §3.1）。
 *
 * <p>脱敏：不返回主节点存储绝对路径、临时文件名或任何凭据。
 */
@Data
public class RunnerPackageVO {

    /** 包摘要（.tar.gz 字节的 sha256，小写十六进制 64 位），不可变标识 */
    private String sha256;
    private String fileName;
    /** manifest.package.version（dev 包带 -dev 后缀） */
    private String version;
    private String os;
    private String arch;
    private Long sizeBytes;
    private String gitCommit;
    private String imageReference;
    private String imageId;
    private String dockerCliVersion;
    private String composeVersion;
    private Integer formatVersion;
    private String uploadedBy;
    private LocalDateTime uploadedAt;
    /** 仅上传响应出现：true 表示相同摘要已存在，本次未新建索引记录 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean existing;
    /** 仅上传响应出现：true 表示本次请求重写了磁盘上的包文件（索引与文件失同步时按摘要修复） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean repaired;
}
