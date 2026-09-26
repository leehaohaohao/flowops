package com.nexa.flowops.service.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexa.flowops.docker.DockerClient;
import com.nexa.flowops.docker.DockerResult;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.entity.DockerNetwork;
import com.nexa.flowops.entity.NetworkProjectGrant;
import com.nexa.flowops.entity.ProjectDefaultNetwork;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.mapper.DockerNetworkMapper;
import com.nexa.flowops.mapper.NetworkProjectGrantMapper;
import com.nexa.flowops.mapper.ProjectDefaultNetworkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2：主节点网络操作（managed/imported、状态对账、失败补偿）。
 * 本机无 Docker，DockerClient 以桩替代；真实 Docker 实测由统筹方在目标环境执行。
 */
class DockerNetworkServiceTest {

    private DockerNetworkMapper networkMapper;
    private NetworkProjectGrantMapper grantMapper;
    private ProjectDefaultNetworkMapper defaultMapper;
    private DeployServiceMapper serviceMapper;
    private DockerClient dockerClient;
    private DockerNetworkService service;

    @BeforeEach
    void setUp() {
        networkMapper = mock(DockerNetworkMapper.class);
        grantMapper = mock(NetworkProjectGrantMapper.class);
        defaultMapper = mock(ProjectDefaultNetworkMapper.class);
        serviceMapper = mock(DeployServiceMapper.class);
        dockerClient = mock(DockerClient.class);
        service = new DockerNetworkService(networkMapper, grantMapper, defaultMapper,
                serviceMapper, dockerClient, new ObjectMapper());
    }

    // ==================== 创建 managed ====================

    @Test
    void createManaged_createsDockerNetworkThenRegisters() {
        when(networkMapper.selectOne(any())).thenReturn(null);
        when(dockerClient.createNetwork("shared-a")).thenReturn(ok());

        DockerNetwork created = service.createManaged("shared-a", "共享A");

        verify(dockerClient).createNetwork("shared-a");
        ArgumentCaptor<DockerNetwork> captor = ArgumentCaptor.forClass(DockerNetwork.class);
        verify(networkMapper).insert(captor.capture());
        assertEquals("shared-a", captor.getValue().getName());
        assertEquals("共享A", captor.getValue().getDisplayName());
        assertEquals(DockerNetwork.SOURCE_MANAGED, captor.getValue().getSource());
        assertEquals(DockerNetwork.OWNER_TYPE_MASTER, captor.getValue().getOwnerType());
        assertEquals(DockerNetwork.MASTER_OWNER_ID, captor.getValue().getOwnerId());
        assertEquals("shared-a", created.getName());
    }

    @Test
    void createManaged_rollsBackDockerEntityWhenRegistrationFails() {
        when(networkMapper.selectOne(any())).thenReturn(null);
        when(dockerClient.createNetwork("shared-b")).thenReturn(ok());
        when(networkMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        when(dockerClient.removeNetwork("shared-b")).thenReturn(ok());

        NetworkException e = assertThrows(NetworkException.class, () -> service.createManaged("shared-b", null));

        assertEquals(NetworkException.CODE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("已回滚 Docker 实体"));
        verify(dockerClient).removeNetwork("shared-b"); // 失败补偿：不伪报成功
    }

    @Test
    void createManaged_rejectsDuplicateWithoutCallingDocker() {
        when(networkMapper.selectOne(any())).thenReturn(existing("shared-a"));

        NetworkException e = assertThrows(NetworkException.class, () -> service.createManaged("shared-a", null));

        assertEquals(NetworkException.CODE_CONFLICT, e.getCode());
        verify(dockerClient, never()).createNetwork(anyString());
    }

    @Test
    void createManaged_rejectsInvalidName() {
        NetworkException e = assertThrows(NetworkException.class, () -> service.createManaged("bad name!", null));

        assertEquals(NetworkException.CODE_INVALID, e.getCode());
        verify(dockerClient, never()).createNetwork(anyString());
    }

    @Test
    void createManaged_reportsDockerNameTakenWithImportHint() {
        when(networkMapper.selectOne(any())).thenReturn(null);
        when(dockerClient.createNetwork("shared-c"))
                .thenReturn(new DockerResult(1, "Error response from daemon: network with name shared-c already exists", 1, "docker network create"));

        NetworkException e = assertThrows(NetworkException.class, () -> service.createManaged("shared-c", null));

        assertEquals(NetworkException.CODE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("导入"), "重名应提示改用导入");
        verify(networkMapper, never()).insert(any());
    }

    // ==================== 导入 imported ====================

    @Test
    void importExisting_registersBridgeNetworkWithoutCreatingEntity() {
        when(networkMapper.selectOne(any())).thenReturn(null);
        when(dockerClient.inspectNetwork("legacy-net")).thenReturn(ok("{\"Name\":\"legacy-net\",\"Driver\":\"bridge\",\"Containers\":{}}"));

        DockerNetwork imported = service.importExisting("legacy-net", "历史网络");

        assertEquals(DockerNetwork.SOURCE_IMPORTED, imported.getSource());
        verify(dockerClient, never()).createNetwork(anyString());
        verify(networkMapper).insert(any());
    }

    @Test
    void importExisting_rejectsNonBridgeDriver() {
        when(networkMapper.selectOne(any())).thenReturn(null);
        when(dockerClient.inspectNetwork("overlay-net")).thenReturn(ok("{\"Name\":\"overlay-net\",\"Driver\":\"overlay\"}"));

        NetworkException e = assertThrows(NetworkException.class, () -> service.importExisting("overlay-net", null));

        assertEquals(NetworkException.CODE_INVALID, e.getCode());
        assertTrue(e.getMessage().contains("bridge"));
    }

    @Test
    void importExisting_rejectsMissingNetwork() {
        when(networkMapper.selectOne(any())).thenReturn(null);
        when(dockerClient.inspectNetwork("ghost")).thenReturn(new DockerResult(1, "No such network: ghost", 1, "docker network inspect"));

        NetworkException e = assertThrows(NetworkException.class, () -> service.importExisting("ghost", null));

        assertEquals(NetworkException.CODE_NOT_REGISTERED, e.getCode());
    }

    @Test
    void importExisting_rejectsBuiltinNetwork() {
        NetworkException e = assertThrows(NetworkException.class, () -> service.importExisting("bridge", null));

        assertEquals(NetworkException.CODE_INVALID, e.getCode());
        verify(dockerClient, never()).inspectNetwork(anyString());
    }

    // ==================== 删除 ====================

    @Test
    void deleteNetwork_rejectsWhenGrantsExist() {
        when(networkMapper.selectById(1L)).thenReturn(existing("shared-a"));
        when(grantMapper.selectCount(any())).thenReturn(1L);

        NetworkException e = assertThrows(NetworkException.class, () -> service.deleteNetwork(1L));

        assertEquals(NetworkException.CODE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("授权"));
        verify(dockerClient, never()).removeNetwork(anyString());
    }

    @Test
    void deleteNetwork_rejectsWhenServiceStillReferences() {
        when(networkMapper.selectById(1L)).thenReturn(existing("shared-a"));
        when(grantMapper.selectCount(any())).thenReturn(0L);
        when(defaultMapper.selectCount(any())).thenReturn(0L);
        when(serviceMapper.selectCount(any())).thenReturn(2L);

        NetworkException e = assertThrows(NetworkException.class, () -> service.deleteNetwork(1L));

        assertEquals(NetworkException.CODE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("2 个服务引用"));
    }

    @Test
    void deleteManaged_rejectsWhenContainersStillConnected() {
        when(networkMapper.selectById(1L)).thenReturn(existing("shared-a"));
        when(grantMapper.selectCount(any())).thenReturn(0L);
        when(defaultMapper.selectCount(any())).thenReturn(0L);
        when(serviceMapper.selectCount(any())).thenReturn(0L);
        when(dockerClient.inspectNetwork("shared-a"))
                .thenReturn(ok("{\"Name\":\"shared-a\",\"Driver\":\"bridge\",\"Containers\":{\"abc\":{}}}"));

        NetworkException e = assertThrows(NetworkException.class, () -> service.deleteNetwork(1L));

        assertEquals(NetworkException.CODE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("容器连接"));
        verify(dockerClient, never()).removeNetwork(anyString());
    }

    @Test
    void deleteManaged_removesDockerEntityAndRegistration() {
        when(networkMapper.selectById(1L)).thenReturn(existing("shared-a"));
        when(grantMapper.selectCount(any())).thenReturn(0L);
        when(defaultMapper.selectCount(any())).thenReturn(0L);
        when(serviceMapper.selectCount(any())).thenReturn(0L);
        when(dockerClient.inspectNetwork("shared-a"))
                .thenReturn(ok("{\"Name\":\"shared-a\",\"Driver\":\"bridge\",\"Containers\":{}}"));
        when(dockerClient.removeNetwork("shared-a")).thenReturn(ok());

        service.deleteNetwork(1L);

        verify(dockerClient).removeNetwork("shared-a");
        verify(networkMapper).deleteById(1L);
    }

    @Test
    void deleteImported_onlyUnregistersAndKeepsDockerEntity() {
        DockerNetwork imported = existing("legacy-net");
        imported.setSource(DockerNetwork.SOURCE_IMPORTED);
        when(networkMapper.selectById(2L)).thenReturn(imported);
        when(grantMapper.selectCount(any())).thenReturn(0L);
        when(defaultMapper.selectCount(any())).thenReturn(0L);
        when(serviceMapper.selectCount(any())).thenReturn(0L);

        service.deleteNetwork(2L);

        verify(dockerClient, never()).removeNetwork(anyString()); // 导入网络取消登记不删除 Docker 实体
        verify(networkMapper).deleteById(2L);
    }

    // ==================== 对账与候选 ====================

    @Test
    void listImportable_filtersBuiltinNonBridgeAndRegistered() {
        when(dockerClient.listNetworks()).thenReturn(ok(String.join("\n",
                "{\"Name\":\"bridge\",\"Driver\":\"bridge\"}",
                "{\"Name\":\"host\",\"Driver\":\"host\"}",
                "{\"Name\":\"overlay-x\",\"Driver\":\"overlay\"}",
                "{\"Name\":\"shared-a\",\"Driver\":\"bridge\"}",
                "{\"Name\":\"legacy-net\",\"Driver\":\"bridge\"}")));
        // 按遍历顺序：先命中已登记的 shared-a，再判定 legacy-net 未登记
        when(networkMapper.selectOne(any())).thenReturn(existing("shared-a")).thenReturn(null);

        List<ImportableNetwork> importable = service.listImportable();

        assertEquals(1, importable.size());
        assertEquals("legacy-net", importable.get(0).name());
    }

    @Test
    void dockerStatus_reportsMissingWhenInspectFails() {
        DockerNetwork network = existing("gone-net");
        when(dockerClient.inspectNetwork("gone-net")).thenReturn(new DockerResult(1, "No such network", 1, "..."));

        assertEquals(DockerNetworkService.STATUS_MISSING, service.dockerStatus(network));
        assertFalse(service.inspect(network).present());
    }

    // ==================== 工具 ====================

    private DockerResult ok() {
        return new DockerResult(0, "", 1, "docker …");
    }

    private DockerResult ok(String output) {
        return new DockerResult(0, output, 1, "docker …");
    }

    private DockerNetwork existing(String name) {
        DockerNetwork network = new DockerNetwork();
        network.setId(1L);
        network.setName(name);
        network.setDisplayName(name);
        network.setSource(DockerNetwork.SOURCE_MANAGED);
        network.setOwnerType(DockerNetwork.OWNER_TYPE_MASTER);
        network.setOwnerId(DockerNetwork.MASTER_OWNER_ID);
        return network;
    }
}
