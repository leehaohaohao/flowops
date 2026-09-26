package com.nexa.flowops.service.network;

import com.nexa.flowops.entity.DockerNetwork;
import com.nexa.flowops.entity.NetworkProjectGrant;
import com.nexa.flowops.entity.ProjectDefaultNetwork;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.mapper.NetworkProjectGrantMapper;
import com.nexa.flowops.mapper.ProjectDefaultNetworkMapper;
import com.nexa.flowops.permission.entity.Project;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.ProjectMapper;
import com.nexa.flowops.permission.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3：网络授权角色矩阵、越权拒绝、引用拒删撤权、Docker 状态不一致时的候选过滤。
 */
class NetworkAuthorizationServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long NETWORK_ID = 1L;

    private NetworkProjectGrantMapper grantMapper;
    private ProjectDefaultNetworkMapper defaultMapper;
    private DeployServiceMapper serviceMapper;
    private DockerNetworkService networkService;
    private PermissionService permissionService;
    private ProjectMapper projectMapper;
    private NetworkAuthorizationService service;

    @BeforeEach
    void setUp() {
        grantMapper = mock(NetworkProjectGrantMapper.class);
        defaultMapper = mock(ProjectDefaultNetworkMapper.class);
        serviceMapper = mock(DeployServiceMapper.class);
        networkService = mock(DockerNetworkService.class);
        permissionService = mock(PermissionService.class);
        projectMapper = mock(ProjectMapper.class);
        service = new NetworkAuthorizationService(grantMapper, defaultMapper, serviceMapper,
                networkService, permissionService, projectMapper);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(new Project());
    }

    // ==================== 角色矩阵 ====================

    @Test
    void requireSuperAdmin_rejectsNonAdmin() {
        when(permissionService.isSuperAdmin()).thenReturn(false);

        NetworkException e = assertThrows(NetworkException.class, () -> service.requireSuperAdmin());

        assertEquals(NetworkException.CODE_FORBIDDEN, e.getCode());
    }

    @Test
    void requireSuperAdmin_allowsAdmin() {
        when(permissionService.isSuperAdmin()).thenReturn(true);

        service.requireSuperAdmin();
    }

    @Test
    void projectNetworkRead_allowsSupervisor() {
        SuperAdminSetup.notSuperAdmin(permissionService, 5L);
        when(permissionService.isSupervisor(5L, PROJECT_ID)).thenReturn(true);

        service.requireProjectNetworkRead(PROJECT_ID);
    }

    @Test
    void projectNetworkRead_allowsEditorWithEditConfig() {
        SuperAdminSetup.notSuperAdmin(permissionService, 6L);
        when(permissionService.isSupervisor(6L, PROJECT_ID)).thenReturn(false);
        when(permissionService.getEffectivePermissions(6L, PROJECT_ID)).thenReturn(Set.of("VIEW", "EDIT_CONFIG"));

        service.requireProjectNetworkRead(PROJECT_ID);
    }

    @Test
    void projectNetworkRead_rejectsPlainMember() {
        SuperAdminSetup.notSuperAdmin(permissionService, 7L);
        when(permissionService.isSupervisor(7L, PROJECT_ID)).thenReturn(false);
        when(permissionService.getEffectivePermissions(7L, PROJECT_ID)).thenReturn(Set.of("VIEW"));

        NetworkException e = assertThrows(NetworkException.class, () -> service.requireProjectNetworkRead(PROJECT_ID));

        assertEquals(NetworkException.CODE_FORBIDDEN, e.getCode());
    }

    @Test
    void projectDefaultWrite_rejectsEditorWithoutSupervisorRole() {
        SuperAdminSetup.notSuperAdmin(permissionService, 6L);
        when(permissionService.isSupervisor(6L, PROJECT_ID)).thenReturn(false);

        NetworkException e = assertThrows(NetworkException.class, () -> service.requireProjectDefaultWrite(PROJECT_ID));

        assertEquals(NetworkException.CODE_FORBIDDEN, e.getCode());
    }

    @Test
    void projectDefaultWrite_allowsSupervisor() {
        SuperAdminSetup.notSuperAdmin(permissionService, 5L);
        when(permissionService.isSupervisor(5L, PROJECT_ID)).thenReturn(true);

        service.requireProjectDefaultWrite(PROJECT_ID);
    }

    // ==================== 授权 ====================

    @Test
    void grant_isIdempotent() {
        when(permissionService.isSuperAdmin()).thenReturn(true);
        when(grantMapper.selectOne(any())).thenReturn(new NetworkProjectGrant());

        service.grant(NETWORK_ID, PROJECT_ID);

        verify(grantMapper, never()).insert(any());
    }

    @Test
    void grant_insertsWhenAbsent() {
        when(permissionService.isSuperAdmin()).thenReturn(true);
        when(grantMapper.selectOne(any())).thenReturn(null);

        service.grant(NETWORK_ID, PROJECT_ID);

        verify(grantMapper).insert(any(NetworkProjectGrant.class));
    }

    @Test
    void grant_rejectsUnregisteredNetwork() {
        when(permissionService.isSuperAdmin()).thenReturn(true);
        when(networkService.requireRegistered(NETWORK_ID))
                .thenThrow(NetworkException.notRegistered("网络未登记: 1"));

        NetworkException e = assertThrows(NetworkException.class, () -> service.grant(NETWORK_ID, PROJECT_ID));

        assertEquals(NetworkException.CODE_NOT_REGISTERED, e.getCode());
    }

    @Test
    void revoke_rejectsWhenNetworkIsProjectDefault() {
        when(permissionService.isSuperAdmin()).thenReturn(true);
        ProjectDefaultNetwork def = new ProjectDefaultNetwork();
        def.setProjectId(PROJECT_ID);
        def.setNetworkId(NETWORK_ID);
        when(defaultMapper.selectById(PROJECT_ID)).thenReturn(def);

        NetworkException e = assertThrows(NetworkException.class, () -> service.revoke(NETWORK_ID, PROJECT_ID));

        assertEquals(NetworkException.CODE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("默认网络"));
    }

    @Test
    void revoke_rejectsWhenServicesStillUseNetwork() {
        when(permissionService.isSuperAdmin()).thenReturn(true);
        when(defaultMapper.selectById(PROJECT_ID)).thenReturn(null);
        when(serviceMapper.selectCount(any())).thenReturn(2L);

        NetworkException e = assertThrows(NetworkException.class, () -> service.revoke(NETWORK_ID, PROJECT_ID));

        assertEquals(NetworkException.CODE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("2 个服务"));
    }

    @Test
    void revoke_deletesGrantWhenNoReferences() {
        when(permissionService.isSuperAdmin()).thenReturn(true);
        when(defaultMapper.selectById(PROJECT_ID)).thenReturn(null);
        when(serviceMapper.selectCount(any())).thenReturn(0L);

        service.revoke(NETWORK_ID, PROJECT_ID);

        verify(grantMapper).delete(any());
    }

    // ==================== 默认值 ====================

    @Test
    void setDefaultNetwork_rejectsNetworkNotGrantedToProject() {
        SuperAdminSetup.notSuperAdmin(permissionService, 5L);
        when(permissionService.isSupervisor(5L, PROJECT_ID)).thenReturn(true);
        when(grantMapper.selectList(any())).thenReturn(List.of());

        NetworkException e = assertThrows(NetworkException.class,
                () -> service.setDefaultNetwork(PROJECT_ID, NETWORK_ID));

        assertEquals(NetworkException.CODE_INVALID, e.getCode());
        verify(defaultMapper, never()).insert(any());
    }

    @Test
    void setDefaultNetwork_insertsWhenGranted() {
        SuperAdminSetup.notSuperAdmin(permissionService, 5L);
        when(permissionService.isSupervisor(5L, PROJECT_ID)).thenReturn(true);
        NetworkProjectGrant grant = new NetworkProjectGrant();
        grant.setNetworkId(NETWORK_ID);
        when(grantMapper.selectList(any())).thenReturn(List.of(grant));
        when(defaultMapper.selectById(PROJECT_ID)).thenReturn(null);

        service.setDefaultNetwork(PROJECT_ID, NETWORK_ID);

        verify(defaultMapper).insert(any(ProjectDefaultNetwork.class));
    }

    @Test
    void setDefaultNetwork_nullClearsDefault() {
        SuperAdminSetup.notSuperAdmin(permissionService, 5L);
        when(permissionService.isSupervisor(5L, PROJECT_ID)).thenReturn(true);

        service.setDefaultNetwork(PROJECT_ID, null);

        verify(defaultMapper).deleteById(PROJECT_ID);
        verify(networkService, never()).requireRegistered(anyLong());
    }

    // ==================== 候选过滤 ====================

    @Test
    void listProjectNetworks_excludesDockerMissingNetworks() {
        SuperAdminSetup.notSuperAdmin(permissionService, 5L);
        when(permissionService.isSupervisor(5L, PROJECT_ID)).thenReturn(true);
        NetworkProjectGrant presentGrant = new NetworkProjectGrant();
        presentGrant.setNetworkId(1L);
        NetworkProjectGrant missingGrant = new NetworkProjectGrant();
        missingGrant.setNetworkId(2L);
        when(grantMapper.selectList(any())).thenReturn(List.of(presentGrant, missingGrant));
        when(networkService.overview(1L)).thenReturn(new NetworkOverview(network(1L, "shared-a"),
                DockerNetworkService.STATUS_PRESENT, 1, 0));
        when(networkService.overview(2L)).thenReturn(new NetworkOverview(network(2L, "gone"),
                DockerNetworkService.STATUS_MISSING, 1, 0));

        List<NetworkOverview> candidates = service.listProjectNetworks(PROJECT_ID);

        assertEquals(1, candidates.size());
        assertEquals("shared-a", candidates.get(0).network().getName());
    }

    @Test
    void listProjectNetworks_skipsGrantWhoseNetworkWasDeleted() {
        SuperAdminSetup.notSuperAdmin(permissionService, 5L);
        when(permissionService.isSupervisor(5L, PROJECT_ID)).thenReturn(true);
        NetworkProjectGrant grant = new NetworkProjectGrant();
        grant.setNetworkId(3L);
        when(grantMapper.selectList(any())).thenReturn(List.of(grant));
        when(networkService.overview(3L)).thenThrow(NetworkException.notRegistered("网络未登记: 3"));

        assertTrue(service.listProjectNetworks(PROJECT_ID).isEmpty());
    }

    private DockerNetwork network(Long id, String name) {
        DockerNetwork network = new DockerNetwork();
        network.setId(id);
        network.setName(name);
        network.setDisplayName(name);
        network.setSource(DockerNetwork.SOURCE_MANAGED);
        return network;
    }

    /** 统一的非超管登录用户桩 */
    static final class SuperAdminSetup {
        private SuperAdminSetup() {
        }

        static void notSuperAdmin(PermissionService permissionService, Long userId) {
            when(permissionService.isSuperAdmin()).thenReturn(false);
            SysUser user = new SysUser();
            user.setId(userId);
            user.setIsSuperAdmin(0);
            when(permissionService.getCurrentUser()).thenReturn(user);
        }
    }
}
