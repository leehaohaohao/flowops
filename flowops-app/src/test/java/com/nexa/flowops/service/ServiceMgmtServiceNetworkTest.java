package com.nexa.flowops.service;

import com.nexa.flowops.dto.UpdateServiceRequest;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.artifact.ArtifactRegistry;
import com.nexa.flowops.service.network.DockerNetworkService;
import com.nexa.flowops.service.network.NetworkAuthorizationService;
import com.nexa.flowops.service.network.NetworkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B4：服务保存 networkId 的校验——节点必须本机、网络已登记、项目已授权。
 */
class ServiceMgmtServiceNetworkTest {

    private static final Long SERVICE_ID = 100L;
    private static final Long PROJECT_ID = 10L;
    private static final Long NETWORK_ID = 5L;

    private DeployServiceMapper serviceMapper;
    private DockerNetworkService networkService;
    private NetworkAuthorizationService authorizationService;
    private ServiceMgmtService service;
    private DeployService existingService;

    @BeforeEach
    void setUp() {
        serviceMapper = mock(DeployServiceMapper.class);
        networkService = mock(DockerNetworkService.class);
        authorizationService = mock(NetworkAuthorizationService.class);
        service = new ServiceMgmtService(serviceMapper, mock(ArtifactRegistry.class),
                networkService, authorizationService);

        existingService = new DeployService();
        existingService.setId(SERVICE_ID);
        existingService.setProjectId(PROJECT_ID);
        existingService.setName("demo");
        existingService.setDeployName("demo");
        existingService.setServiceType("backend");
    }

    @Test
    void update_rejectsSharedNetworkWithRunnerNode() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(existingService);
        UpdateServiceRequest req = updateRequest("runner-1", NETWORK_ID);

        NetworkException e = assertThrows(NetworkException.class, () -> service.updateService(SERVICE_ID, req));

        assertEquals(NetworkException.CODE_INVALID, e.getCode());
        verify(serviceMapper, never()).updateById(any());
    }

    @Test
    void update_rejectsSharedNetworkWithAutoNode() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(existingService);
        UpdateServiceRequest req = updateRequest("auto", NETWORK_ID);

        NetworkException e = assertThrows(NetworkException.class, () -> service.updateService(SERVICE_ID, req));

        assertEquals(NetworkException.CODE_INVALID, e.getCode());
    }

    @Test
    void update_rejectsNetworkNotGrantedToProject() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(existingService);
        when(authorizationService.isGranted(PROJECT_ID, NETWORK_ID)).thenReturn(false);
        UpdateServiceRequest req = updateRequest(null, NETWORK_ID);

        NetworkException e = assertThrows(NetworkException.class, () -> service.updateService(SERVICE_ID, req));

        assertEquals(NetworkException.CODE_FORBIDDEN, e.getCode());
        verify(serviceMapper, never()).updateById(any());
    }

    @Test
    void update_rejectsUnregisteredNetwork() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(existingService);
        when(networkService.requireRegistered(NETWORK_ID))
                .thenThrow(NetworkException.notRegistered("网络未登记: 5"));
        UpdateServiceRequest req = updateRequest(null, NETWORK_ID);

        NetworkException e = assertThrows(NetworkException.class, () -> service.updateService(SERVICE_ID, req));

        assertEquals(NetworkException.CODE_NOT_REGISTERED, e.getCode());
    }

    @Test
    void update_savesNetworkWhenLocalNodeAndGranted() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(existingService);
        when(authorizationService.isGranted(PROJECT_ID, NETWORK_ID)).thenReturn(true);
        UpdateServiceRequest req = updateRequest(null, NETWORK_ID);

        service.updateService(SERVICE_ID, req);

        ArgumentCaptor<DeployService> captor = ArgumentCaptor.forClass(DeployService.class);
        verify(serviceMapper).updateById(captor.capture());
        assertEquals(NETWORK_ID, captor.getValue().getNetworkId());
        verify(networkService).requireRegistered(NETWORK_ID);
    }

    @Test
    void update_clearsNetworkWhenNullSubmitted() {
        existingService.setNetworkId(NETWORK_ID);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(existingService);
        UpdateServiceRequest req = updateRequest(null, null);

        service.updateService(SERVICE_ID, req);

        ArgumentCaptor<DeployService> captor = ArgumentCaptor.forClass(DeployService.class);
        verify(serviceMapper).updateById(captor.capture());
        assertNull(captor.getValue().getNetworkId(), "networkId=null 表示清除共享网络");
        verify(networkService, never()).requireRegistered(any());
    }

    @Test
    void update_withoutNetwork_skipsNetworkValidation() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(existingService);
        UpdateServiceRequest req = updateRequest("runner-1", null);

        service.updateService(SERVICE_ID, req);

        verify(serviceMapper).updateById(any());
        verify(authorizationService, never()).isGranted(any(), any());
    }

    private UpdateServiceRequest updateRequest(String nodeId, Long networkId) {
        UpdateServiceRequest req = new UpdateServiceRequest();
        req.setNodeId(nodeId);
        req.setNetworkId(networkId);
        return req;
    }
}
