package com.nexa.flowops.util;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.InfoCmdImpl;
import org.springframework.stereotype.Component;

@Component
public class DockerUtil {

    private final DockerClient dockerClient;

    public DockerUtil() {
        this.dockerClient = DockerClientImpl.getInstance("tcp://localhost:2375");
    }

    public boolean isContainerRunning(String containerName) {
        try {
            return dockerClient.listContainersCmd()
                    .withShowAll(false)
                    .exec()
                    .stream()
                    .anyMatch(c -> c.getNames()[0].equals("/" + containerName));
        } catch (Exception e) {
            return false;
        }
    }

    public String getContainerLog(String containerName, Integer tail) {
        try {
            return dockerClient.logContainerCmd(containerName)
                    .withTail(tail)
                    .exec(new org.apache.commons.io.output.StringBuilderWriter())
                    .toString();
        } catch (Exception e) {
            return "获取日志失败: " + e.getMessage();
        }
    }
}
