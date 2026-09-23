package com.nexa.flowops.service.artifact;

import com.nexa.flowops.entity.DeployArtifact;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 产物存储抽象：按注册表元数据打开产物内容（单文件直读；DIST 目录确定性打包）
 */
public interface ArtifactStore {

    /** 产物是否存在 */
    boolean exists(DeployArtifact artifact);

    /**
     * 准备产物内容（含大小与 sha256）。调用方必须 close。
     */
    PreparedArtifact prepare(DeployArtifact artifact) throws IOException;

    /**
     * 已准备的产物：流 + 大小 + 校验和。close 释放流并清理临时文件。
     */
    record PreparedArtifact(InputStream stream, long size, String checksum, java.io.File tempFile)
            implements Closeable {

        @Override
        public void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }
}
