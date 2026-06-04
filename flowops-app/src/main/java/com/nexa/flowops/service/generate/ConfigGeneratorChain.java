package com.nexa.flowops.service.generate;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class ConfigGeneratorChain {

    private final List<ConfigGenerator> generators;

    public ConfigGeneratorChain(List<ConfigGenerator> generators) {
        this.generators = generators;
    }

    public void generate(DeployContext context) throws IOException {
        for (ConfigGenerator generator : generators) {
            if (generator.supports(context)) {
                generator.generate(context);
            }
        }
    }
}
