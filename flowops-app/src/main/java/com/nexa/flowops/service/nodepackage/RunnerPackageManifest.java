package com.nexa.flowops.service.nodepackage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 发布包 {@code manifest.json} 的严格映射（契约 {@code docs/runner-package-format.md} §3）。
 *
 * <p>未知字段一律导致解析失败（{@code @JsonIgnoreProperties(ignoreUnknown = false)}）：
 * 格式 v1 的字段集固定，新增字段必须先提升 {@code packageFormatVersion}。
 * 这样也阻断"把节点凭据塞进 manifest 附加字段"的路径（P3-C：包与凭据分离）。
 *
 * <p>字段用包装类型声明，缺失时显式为 {@code null}，便于给出精确的校验错误。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class RunnerPackageManifest {

    private Integer packageFormatVersion;
    /** JSON 字段名为 {@code package}（Java 关键字，故字段名用 pkg） */
    @JsonProperty("package")
    private PackageSection pkg;
    private BinarySection binary;
    private ImageSection image;
    private TemplateSection templates;
    private List<Member> members;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class PackageSection {
        private String name;
        private String version;
        private String os;
        private String arch;
        private String fileName;
        private String gitCommit;
        private Long sourceDateEpoch;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class BinarySection {
        private String path;
        private String goVersion;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class ImageSection {
        private String path;
        private String reference;
        private String imageId;
        private String dockerCliVersion;
        private String composeVersion;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class TemplateSection {
        private String systemd;
        private String compose;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Member {
        private String path;
        private Long size;
        private String mode;
    }
}
