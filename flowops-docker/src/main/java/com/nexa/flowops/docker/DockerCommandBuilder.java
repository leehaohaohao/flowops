package com.nexa.flowops.docker;

import java.util.List;

/**
 * Docker 命令的固定参数构造。
 *
 * <p>主节点网络操作只允许使用这里列出的命令形态：不接受任意 Shell 命令、不接受调用方传入
 * 额外参数（subnet/gateway 等），便于审计与单元测试。网络名由调用方严格校验后传入。
 */
public final class DockerCommandBuilder {

    private DockerCommandBuilder() {
    }

    /** docker network inspect --format {{json .}} &lt;name&gt;（不存在时退出码非 0） */
    public static List<String> networkInspect(String name) {
        return List.of("docker", "network", "inspect", "--format", "{{json .}}", name);
    }

    /** docker network create --driver bridge &lt;name&gt;（使用 Docker 默认地址分配） */
    public static List<String> networkCreateBridge(String name) {
        return List.of("docker", "network", "create", "--driver", "bridge", name);
    }

    /** docker network rm &lt;name&gt; */
    public static List<String> networkRemove(String name) {
        return List.of("docker", "network", "rm", name);
    }

    /** docker network ls --format {{json .}}（每行一个 JSON，由调用方过滤 driver 与内置网络） */
    public static List<String> networkList() {
        return List.of("docker", "network", "ls", "--format", "{{json .}}");
    }
}
