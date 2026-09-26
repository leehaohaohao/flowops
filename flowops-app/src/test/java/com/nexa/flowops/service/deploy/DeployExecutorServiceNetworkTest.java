package com.nexa.flowops.service.deploy;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.entity.DockerNetwork;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.network.DockerNetworkService;
import com.nexa.flowops.service.network.NetworkAuthorizationService;
import com.nexa.flowops.service.network.NetworkException;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.QueryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B4：部署前共享网络校验——节点与网络互斥、Docker 实体必须存在、项目授权仍然有效；
 * 未选网络的服务调度行为不变。
 */
class DeployExecutorServiceNetworkTest {

    private static final Long SERVICE_ID = 100L;
    private static final Long PROJECT_ID = 10L;
    private static final Long NETWORK_ID = 5L;

    private DeployServiceMapper serviceMapper;
    private RemoteDeployDispatcher remoteDispatcher;
    private LocalDeployRunner localRunner;
    private DockerNetworkService networkService;
    private NetworkAuthorizationService authorizationService;
    private DeployExecutorService service;
    private DeployService deployService;

    @BeforeEach
    void setUp() {
        serviceMapper = mock(DeployServiceMapper.class);
        remoteDispatcher = mock(RemoteDeployDispatcher.class);
        localRunner = mock(LocalDeployRunner.class);
        networkService = mock(DockerNetworkService.class);
        authorizationService = mock(NetworkAuthorizationService.class);
        service = new DeployExecutorService(serviceMapper, mock(UploadHelper.class), localRunner,
                remoteDispatcher, mock(DeployLogHelper.class), mock(NodeService.class),
                mock(QueryManager.class), networkService, authorizationService);

        deployService = new DeployService();
        deployService.setId(SERVICE_ID);
        deployService.setProjectId(PROJECT_ID);
        deployService.setName("demo");
        deployService.setDeployName("demo");
        deployService.setServiceType("backend");
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(deployService);
    }

    @Test
    void deploy_rejectsSharedNetworkWithRunnerNodeBeforeDispatch() {
        deployService.setNetworkId(NETWORK_ID);
        deployService.setNodeId("auto");

        Result<Void> result = service.deploy(SERVICE_ID);

        assertEquals(NetworkException.CODE_INVALID, result.getCode());
        verify(remoteDispatcher, never()).dispatch(any(), any(), any(), any(), any());
        verify(localRunner, never()).runStart(any(), any());
    }

    @Test
    void deploy_rejectsWhenDockerEntityMissing() {
        deployService.setNetworkId(NETWORK_ID);
        when(networkService.requireRegistered(NETWORK_ID)).thenReturn(network("shared-a"));
        when(networkService.inspect(any())).thenReturn(
                new DockerNetworkService.NetworkInspect(false, "shared-a", null, 0));

        Result<Void> result = service.deploy(SERVICE_ID);

        assertEquals(NetworkException.CODE_DOCKER_MISSING, result.getCode());
        verify(localRunner, never()).runStart(any(), any());
    }

    @Test
    void deploy_rejectsWhenGrantRevoked() {
        deployService.setNetworkId(NETWORK_ID);
        when(networkService.requireRegistered(NETWORK_ID)).thenReturn(network("shared-a"));
        when(networkService.inspect(any())).thenReturn(
                new DockerNetworkService.NetworkInspect(true, "shared-a", "bridge", 0));
        when(authorizationService.isGranted(PROJECT_ID, NETWORK_ID)).thenReturn(false);

        Result<Void> result = service.deploy(SERVICE_ID);

        assertEquals(NetworkException.CODE_FORBIDDEN, result.getCode());
        verify(localRunner, never()).runStart(any(), any());
    }

    @Test
    void deploy_withoutNetwork_keepsLocalSchedulingBehaviour() {
        deployService.setNetworkId(null);
        when(localRunner.runStart(any(), any())).thenReturn(Result.ok("部署成功"));
        when(remoteDispatcher.resolveTargetNode(deployService)).thenReturn(null);

        Result<Void> result = service.deploy(SERVICE_ID);

        assertFalse(result.getCode() != 200, "无网络服务应正常走本机部署");
        verify(localRunner).runStart(any(), any());
        verify(networkService, never()).requireRegistered(any());
    }

    private DockerNetwork network(String name) {
        DockerNetwork network = new DockerNetwork();
        network.setId(NETWORK_ID);
        network.setName(name);
        network.setDisplayName(name);
        network.setSource(DockerNetwork.SOURCE_MANAGED);
        return network;
    }
}
