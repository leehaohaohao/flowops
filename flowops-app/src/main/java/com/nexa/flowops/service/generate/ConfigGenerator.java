package com.nexa.flowops.service.generate;

import java.io.IOException;

public interface ConfigGenerator {

    boolean supports(DeployContext context);

    void generate(DeployContext context) throws IOException;
}
