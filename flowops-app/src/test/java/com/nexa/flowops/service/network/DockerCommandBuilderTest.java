package com.nexa.flowops.service.network;

import com.nexa.flowops.docker.DockerCommandBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 主节点网络操作只允许固定参数：不接受任意 Shell 命令、不接受 subnet/gateway 等额外参数。
 */
class DockerCommandBuilderTest {

    @Test
    void networkInspect_usesFixedArguments() {
        assertEquals(List.of("docker", "network", "inspect", "--format", "{{json .}}", "flowops-shared"),
                DockerCommandBuilder.networkInspect("flowops-shared"));
    }

    @Test
    void networkCreateBridge_usesDefaultAddressAllocationOnly() {
        List<String> command = DockerCommandBuilder.networkCreateBridge("flowops-shared");

        assertEquals(List.of("docker", "network", "create", "--driver", "bridge", "flowops-shared"), command);
        assertFalse(command.contains("--subnet"), "不允许指定 subnet");
        assertFalse(command.contains("--gateway"), "不允许指定 gateway");
        assertFalse(command.contains("--internal"), "不允许指定 internal");
    }

    @Test
    void networkRemove_usesFixedArguments() {
        assertEquals(List.of("docker", "network", "rm", "flowops-shared"),
                DockerCommandBuilder.networkRemove("flowops-shared"));
    }

    @Test
    void networkList_usesFixedArguments() {
        assertEquals(List.of("docker", "network", "ls", "--format", "{{json .}}"),
                DockerCommandBuilder.networkList());
    }
}
