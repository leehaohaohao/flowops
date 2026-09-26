package com.nexa.flowops.service.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexa.flowops.entity.DeployService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4：选中共享网络时生成 private default + external shared 双网络与唯一别名；
 * 未选网络时输出与既有行为一致（不出现 networks 段落）。
 */
class ComposeYmlGeneratorNetworkTest {

    private static final long SERVICE_ID = 1001L;

    @TempDir
    Path volumeDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ComposeYmlGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ComposeYmlGenerator();
        ReflectionTestUtils.setField(generator, "logsBasePath", volumeDir.resolve("logs").toString());
    }

    @Test
    void fullstackWithSharedNetwork_joinsBothNetworksWithUniqueAliases() throws Exception {
        DeployContext context = context(fullstackService("""
                [{"hostPort":8080,"containerPort":8080,"target":"backend","primary":true,"expose":false},
                 {"hostPort":80,"containerPort":80,"target":"frontend","primary":true,"expose":false}]
                """));
        context.setSharedNetworkName("flowops-shared");

        generator.generate(context);
        String compose = generated();

        // 每个子服务同时加入私有 default 与 external shared
        assertTrue(compose.contains("  backend:\n"), compose);
        assertTrue(compose.contains("      default: {}"), compose);
        assertTrue(compose.contains("    name: flowops-shared"), compose);
        assertTrue(compose.contains("    external: true"), compose);
        // 共享网络上的唯一别名
        assertTrue(compose.contains("- flowops-svc-" + SERVICE_ID + "-backend"), compose);
        assertTrue(compose.contains("- flowops-svc-" + SERVICE_ID + "-frontend"), compose);
        // 全栈内部 frontend -> backend 仍依赖私有网络
        assertTrue(compose.contains("    depends_on:\n      - backend"), compose);
    }

    @Test
    void withoutSharedNetwork_outputHasNoNetworksSection() throws Exception {
        DeployContext context = context(fullstackService("""
                [{"hostPort":8080,"containerPort":8080,"target":"backend","primary":true,"expose":false},
                 {"hostPort":80,"containerPort":80,"target":"frontend","primary":true,"expose":false}]
                """));

        generator.generate(context);
        String compose = generated();

        assertFalse(compose.contains("networks:"), "未选网络的服务不得出现 networks 段落");
        assertFalse(compose.contains("flowops-svc-"), compose);
        assertTrue(compose.contains("    depends_on:\n      - backend"), compose);
    }

    @Test
    void backendWithSharedNetwork_addsAlias() throws Exception {
        DeployService service = baseService("backend");
        service.setPortMappings("""
                [{"hostPort":8080,"containerPort":8080,"primary":true,"expose":false}]
                """);
        DeployContext context = context(service);
        context.setSharedNetworkName("flowops-shared");

        generator.generate(context);
        String compose = generated();

        assertTrue(compose.contains("- flowops-svc-" + SERVICE_ID + "-backend"), compose);
        assertFalse(compose.contains("- flowops-svc-" + SERVICE_ID + "-frontend"), compose);
        assertTrue(compose.contains("  shared:\n    external: true\n    name: flowops-shared"), compose);
    }

    @Test
    void legacyGenerationPath_alsoAppliesSharedNetwork() throws Exception {
        // portMappings 为空走 legacy 分支，同样要输出双网络
        DeployContext context = context(fullstackService(null));
        context.setSharedNetworkName("flowops-shared");

        generator.generate(context);
        String compose = generated();

        assertTrue(compose.contains("- flowops-svc-" + SERVICE_ID + "-backend"), compose);
        assertTrue(compose.contains("- flowops-svc-" + SERVICE_ID + "-frontend"), compose);
        assertTrue(compose.contains("  shared:\n    external: true\n    name: flowops-shared"), compose);
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedCompose_isValidYamlWithExpectedNetworkStructure() throws Exception {
        DeployContext context = context(fullstackService("""
                [{"hostPort":8080,"containerPort":8080,"target":"backend","primary":true,"expose":false},
                 {"hostPort":80,"containerPort":80,"target":"frontend","primary":true,"expose":false}]
                """));
        context.setSharedNetworkName("flowops-shared");
        generator.generate(context);

        Map<String, Object> root = new Yaml().load(generated());
        Map<String, Object> networks = (Map<String, Object>) root.get("networks");
        assertNotNull(networks, "选中共享网络时必须输出 top-level networks");
        assertNotNull(networks.get("default"), "私有 default 网络必须声明");
        Map<String, Object> shared = (Map<String, Object>) networks.get("shared");
        assertEquals(Boolean.TRUE, shared.get("external"));
        assertEquals("flowops-shared", shared.get("name"));

        Map<String, Object> services = (Map<String, Object>) root.get("services");
        Map<String, Object> backend = (Map<String, Object>) services.get("backend");
        Map<String, Object> backendNetworks = (Map<String, Object>) backend.get("networks");
        assertTrue(backendNetworks.containsKey("default"), "子服务必须保留私有 default 网络");
        assertEquals(List.of("flowops-svc-" + SERVICE_ID + "-backend"),
                ((Map<String, Object>) backendNetworks.get("shared")).get("aliases"));

        Map<String, Object> frontend = (Map<String, Object>) services.get("frontend");
        Map<String, Object> frontendNetworks = (Map<String, Object>) frontend.get("networks");
        assertEquals(List.of("flowops-svc-" + SERVICE_ID + "-frontend"),
                ((Map<String, Object>) frontendNetworks.get("shared")).get("aliases"));
        assertEquals(List.of("backend"), frontend.get("depends_on"), "全栈内部 frontend -> backend 依赖不变");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedComposeWithoutNetwork_hasNoNetworksKey() throws Exception {
        DeployContext context = context(fullstackService("""
                [{"hostPort":8080,"containerPort":8080,"target":"backend","primary":true,"expose":false},
                 {"hostPort":80,"containerPort":80,"target":"frontend","primary":true,"expose":false}]
                """));

        generator.generate(context);

        Map<String, Object> root = new Yaml().load(generated());
        assertNull(root.get("networks"), "未选网络时不得出现 top-level networks");
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        Map<String, Object> backend = (Map<String, Object>) services.get("backend");
        assertNull(backend.get("networks"), "未选网络时子服务不得出现 networks");
    }

    // ==================== 工具 ====================

    private DeployContext context(DeployService service) {
        service.setVolumeDir(volumeDir.toString());
        return DeployContext.from(service, objectMapper);
    }

    private String generated() throws Exception {
        return Files.readString(volumeDir.resolve("docker-compose.yml"));
    }

    private DeployService fullstackService(String portMappings) {
        DeployService service = baseService("fullstack");
        service.setPortMappings(portMappings);
        service.setServiceConfig("""
                {"backend":{"containerPort":8080},"frontend":{"containerPort":80}}
                """);
        return service;
    }

    private DeployService baseService(String serviceType) {
        DeployService service = new DeployService();
        service.setId(SERVICE_ID);
        service.setProjectId(10L);
        service.setName("demo");
        service.setDeployName("demo");
        service.setServiceType(serviceType);
        service.setVolumeDir(volumeDir.toString());
        return service;
    }
}
