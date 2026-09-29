package com.nexa.flowops.service.nodepackage;

import java.util.List;

/**
 * 一次成功校验后的发布包事实（不做任何持久化）。
 *
 * @param sha256             {@code .tar.gz} 文件字节的 SHA-256（小写十六进制 64 位），包身份
 * @param sizeBytes          压缩包字节数
 * @param fileName           实际文件名（已与 manifest 一致）
 * @param version            manifest.package.version（dev 包带 {@code -dev} 后缀）
 * @param os                 manifest.package.os（linux）
 * @param arch               manifest.package.arch（amd64）
 * @param formatVersion      manifest.packageFormatVersion（当前 1）
 * @param gitCommit          构建提交（40 位小写十六进制）
 * @param sourceDateEpoch    归档内全部成员的 mtime
 * @param imageReference     manifest.image.reference
 * @param imageId            manifest.image.imageId
 * @param dockerCliVersion   镜像内 docker CLI 版本
 * @param composeVersion     镜像内 compose 版本
 * @param members            归档成员路径（升序）
 */
public record RunnerPackageInspection(
        String sha256,
        long sizeBytes,
        String fileName,
        String version,
        String os,
        String arch,
        int formatVersion,
        String gitCommit,
        long sourceDateEpoch,
        String imageReference,
        String imageId,
        String dockerCliVersion,
        String composeVersion,
        List<String> members) {
}
