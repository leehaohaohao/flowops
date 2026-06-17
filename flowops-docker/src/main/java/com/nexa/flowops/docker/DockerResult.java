package com.nexa.flowops.docker;

public record DockerResult(int exitCode, String output, long elapsedMs, String command) {

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public String outputTail(int maxLen) {
        if (output == null || output.length() <= maxLen) {
            return output;
        }
        return "..." + output.substring(output.length() - maxLen);
    }
}
