package com.nexa.flowops.service.network;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主节点 Docker 用户自定义 bridge 网络的登记与操作（B2）。
 *
 * <p>规则（见 docs/2026-09-26-node-scoped-docker-network-design-plan.md）：
 * <ul>
 *   <li>只操作主节点本机 Docker，且只用 {@code DockerCommandBuilder} 的固定参数命令</li>
 *   <li>只创建使用 Docker 默认地址分配的用户自定义 {@code bridge}；导入时先 inspect 识别既有网络</li>
 *   <li>区分 managed（平台创建，删除时删 Docker 实体）与 imported（仅取消登记，保留实体）</li>
 *   <li>数据库写入失败时回滚已创建的 Docker 实体，不伪报成功</li>
 *   <li>删除前检查授权、项目默认值与服务引用；managed 网络仍有容器连接时拒绝删除</li>
 * </ul>
 */
@Service
public class DockerNetworkService {

    private static final Logger log = LoggerFactory.getLogger(DockerNetworkService.class);

    /** Docker 状态 */
    public static final String STATUS_PRESENT = "PRESENT";
    public static final String STATUS_MISSING = "MISSING";

    /** Docker 网络名规则：字母数字开头，允许 _ . -，长度 1..128 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$");

    /** Docker 内置网络，不可登记 */
    private static final Set<String> BUILTIN_NETWORKS = Set.of("bridge", "host", "none");

    private final DockerNetworkMapper networkMapper;
    private final NetworkProjectGrantMapper grantMapper;
    private final ProjectDefaultNetworkMapper defaultNetworkMapper;
    private final DeployServiceMapper serviceMapper;
    private final DockerClient dockerClient;
    private final ObjectMapper objectMapper;

    public DockerNetworkService(DockerNetworkMapper networkMapper,
                                NetworkProjectGrantMapper grantMapper,
                                ProjectDefaultNetworkMapper defaultNetworkMapper,
                                DeployServiceMapper serviceMapper,
                                DockerClient dockerClient,
                                ObjectMapper objectMapper) {
        this.networkMapper = networkMapper;
        this.grantMapper = grantMapper;
        this.defaultNetworkMapper = defaultNetworkMapper;
        this.serviceMapper = serviceMapper;
        this.dockerClient = dockerClient;
        this.objectMapper = objectMapper;
    }

    // ==================== 查询 ====================

    /** 已登记网络 + 主节点 Docker 状态 + 授权项目数 + 引用服务数 */
    public List<NetworkOverview> listOverview() {
        List<DockerNetwork> networks = networkMapper.selectList(
                new LambdaQueryWrapper<DockerNetwork>().orderByAsc(DockerNetwork::getId));
        List<NetworkOverview> result = new ArrayList<>(networks.size());
        for (DockerNetwork network : networks) {
            result.add(toOverview(network));
        }
        return result;
    }

    public NetworkOverview overview(Long networkId) {
        return toOverview(requireRegistered(networkId));
    }

    /** 按主键取已登记网络；未登记抛 404 */
    public DockerNetwork requireRegistered(Long networkId) {
        if (networkId == null) {
            throw NetworkException.notRegistered("网络未登记");
        }
        DockerNetwork network = networkMapper.selectById(networkId);
        if (network == null) {
            throw NetworkException.notRegistered("网络未登记: " + networkId);
        }
        return network;
    }

    /** 主节点归属下按名称查已登记网络 */
    public Optional<DockerNetwork> findMasterNetworkByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(networkMapper.selectOne(new LambdaQueryWrapper<DockerNetwork>()
                .eq(DockerNetwork::getName, name.trim())
                .eq(DockerNetwork::getOwnerType, DockerNetwork.OWNER_TYPE_MASTER)
                .eq(DockerNetwork::getOwnerId, DockerNetwork.MASTER_OWNER_ID)));
    }

    /** 主节点 Docker 中未登记、可导入的用户自定义 bridge 网络（排除内置网络） */
    public List<ImportableNetwork> listImportable() {
        DockerResult result = dockerClient.listNetworks();
        if (!result.isSuccess()) {
            throw NetworkException.dockerError("读取主节点 Docker 网络失败: " + tail(result.output()));
        }
        List<ImportableNetwork> importable = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            Map<String, Object> item = parseJsonLine(line);
            if (item == null) {
                continue;
            }
            String name = asString(item.get("Name"));
            String driver = asString(item.get("Driver"));
            if (name == null || name.isBlank() || BUILTIN_NETWORKS.contains(name)) {
                continue;
            }
            if (!"bridge".equals(driver)) {
                continue; // 只支持用户自定义 bridge
            }
            if (findMasterNetworkByName(name).isPresent()) {
                continue; // 已登记的不再作为候选
            }
            importable.add(new ImportableNetwork(name, driver));
        }
        return importable;
    }

    /** inspect 结果：是否存在、driver、容器连接数 */
    public NetworkInspect inspect(DockerNetwork network) {
        return inspectByName(network == null ? null : network.getName());
    }

    public NetworkInspect inspectByName(String name) {
        if (name == null || name.isBlank()) {
            return new NetworkInspect(false, null, null, 0);
        }
        DockerResult result = dockerClient.inspectNetwork(name);
        if (!result.isSuccess()) {
            return new NetworkInspect(false, name, null, 0);
        }
        Map<String, Object> item = parseJsonLine(firstNonBlankLine(result.output()));
        if (item == null) {
            return new NetworkInspect(false, name, null, 0);
        }
        return new NetworkInspect(true, asString(item.get("Name")), asString(item.get("Driver")),
                containerCount(item));
    }

    public String dockerStatus(DockerNetwork network) {
        return inspect(network).present() ? STATUS_PRESENT : STATUS_MISSING;
    }

    public long countGrants(Long networkId) {
        return grantMapper.selectCount(new LambdaQueryWrapper<NetworkProjectGrant>()
                .eq(NetworkProjectGrant::getNetworkId, networkId));
    }

    public long countProjectDefaults(Long networkId) {
        return defaultNetworkMapper.selectCount(new LambdaQueryWrapper<ProjectDefaultNetwork>()
                .eq(ProjectDefaultNetwork::getNetworkId, networkId));
    }

    public long countServiceRefs(Long networkId) {
        return serviceMapper.selectCount(new LambdaQueryWrapper<DeployService>()
                .eq(DeployService::getNetworkId, networkId));
    }

    // ==================== 变更 ====================

    /** 创建并登记 managed 网络：先 docker create，再登记；登记失败回滚 Docker 实体 */
    public DockerNetwork createManaged(String name, String displayName) {
        String validName = validateName(name);
        requireNameNotRegistered(validName);

        DockerResult create = dockerClient.createNetwork(validName);
        if (!create.isSuccess()) {
            String output = tail(create.output());
            if (output != null && output.contains("already exists")) {
                throw NetworkException.conflict("Docker 中已存在同名网络「" + validName + "」，请改用导入");
            }
            throw NetworkException.dockerError("创建 Docker 网络失败: " + output);
        }

        DockerNetwork entity = newNetwork(validName, displayName, DockerNetwork.SOURCE_MANAGED);
        try {
            networkMapper.insert(entity);
        } catch (Exception e) {
            DockerResult rollback = dockerClient.removeNetwork(validName);
            log.warn("网络登记失败，已尝试回滚 Docker 实体: name={}, rollbackSuccess={}, err={}",
                    validName, rollback.isSuccess(), e.getMessage());
            throw NetworkException.conflict("网络登记失败，已回滚 Docker 实体: " + e.getMessage());
        }
        log.info("已创建并登记 managed 网络: name={}, id={}", validName, entity.getId());
        return entity;
    }

    /** 登记既有网络为 imported：先 inspect 确认存在且为 bridge，不创建 Docker 实体 */
    public DockerNetwork importExisting(String name, String displayName) {
        String validName = validateName(name);
        if (BUILTIN_NETWORKS.contains(validName)) {
            throw NetworkException.invalid("Docker 内置网络不可登记: " + validName);
        }
        requireNameNotRegistered(validName);

        NetworkInspect inspect = inspectByName(validName);
        if (!inspect.present()) {
            throw NetworkException.notRegistered("Docker 中不存在该网络: " + validName);
        }
        if (!"bridge".equals(inspect.driver())) {
            throw NetworkException.invalid("只支持 bridge 网络，实际 driver=" + inspect.driver());
        }

        DockerNetwork entity = newNetwork(validName, displayName, DockerNetwork.SOURCE_IMPORTED);
        networkMapper.insert(entity);
        log.info("已导入并登记 imported 网络: name={}, id={}", validName, entity.getId());
        return entity;
    }

    /**
     * 删除网络登记：两类网络有授权、默认值或服务引用时均拒绝；
     * managed 网络删除 Docker 实体（仍有容器连接则拒绝），imported 网络仅取消登记。
     */
    public void deleteNetwork(Long networkId) {
        DockerNetwork network = requireRegistered(networkId);

        long grants = countGrants(networkId);
        if (grants > 0) {
            throw NetworkException.conflict("该网络仍有 " + grants + " 个项目授权，请先撤销授权");
        }
        long defaults = countProjectDefaults(networkId);
        if (defaults > 0) {
            throw NetworkException.conflict("该网络仍是 " + defaults + " 个项目的默认网络，请先清除默认值");
        }
        long refs = countServiceRefs(networkId);
        if (refs > 0) {
            throw NetworkException.conflict("该网络仍被 " + refs + " 个服务引用，请先解除服务网络选择");
        }

        if (DockerNetwork.SOURCE_MANAGED.equals(network.getSource())) {
            NetworkInspect inspect = inspect(network);
            if (inspect.present()) {
                if (inspect.containerCount() > 0) {
                    throw NetworkException.conflict("网络仍有 " + inspect.containerCount() + " 个容器连接，拒绝删除");
                }
                DockerResult remove = dockerClient.removeNetwork(network.getName());
                if (!remove.isSuccess()) {
                    throw NetworkException.dockerError("删除 Docker 网络失败: " + tail(remove.output()));
                }
            } else {
                log.warn("managed 网络在主节点 Docker 中已缺失，仅取消登记: name={}", network.getName());
            }
        } else {
            log.info("imported 网络仅取消登记，保留 Docker 实体: name={}", network.getName());
        }

        networkMapper.deleteById(networkId);
        log.info("已删除网络登记: name={}, id={}, source={}", network.getName(), networkId, network.getSource());
    }

    // ==================== 内部 ====================

    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw NetworkException.invalid("网络名不能为空");
        }
        String trimmed = name.trim();
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw NetworkException.invalid("网络名只能包含字母、数字、下划线、点和连字符，且以字母或数字开头（最长 128）");
        }
        return trimmed;
    }

    private void requireNameNotRegistered(String name) {
        if (findMasterNetworkByName(name).isPresent()) {
            throw NetworkException.conflict("网络已登记: " + name);
        }
    }

    private DockerNetwork newNetwork(String name, String displayName, String source) {
        DockerNetwork entity = new DockerNetwork();
        entity.setName(name);
        entity.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName.trim());
        entity.setSource(source);
        entity.setOwnerType(DockerNetwork.OWNER_TYPE_MASTER);
        entity.setOwnerId(DockerNetwork.MASTER_OWNER_ID);
        return entity;
    }

    private NetworkOverview toOverview(DockerNetwork network) {
        return new NetworkOverview(network, dockerStatus(network),
                countGrants(network.getId()), countServiceRefs(network.getId()));
    }

    @SuppressWarnings("unchecked")
    private long containerCount(Map<String, Object> inspectItem) {
        Object containers = inspectItem.get("Containers");
        if (containers instanceof Map<?, ?> map) {
            return map.size();
        }
        return 0;
    }

    private Map<String, Object> parseJsonLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(line.trim(), LinkedHashMap.class);
        } catch (Exception e) {
            log.warn("解析 Docker 网络 JSON 失败: {}", line, e);
            return null;
        }
    }

    private String firstNonBlankLine(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\n")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String tail(String output) {
        if (output == null) {
            return null;
        }
        String one = output.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(one.length() - 200) : one;
    }

    /** inspect 结果 */
    public record NetworkInspect(boolean present, String name, String driver, long containerCount) {
    }
}
