package com.nexa.flowops.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.common.util.DigestUtil;
import com.nexa.flowops.dto.NexaNodeVO;
import com.nexa.flowops.dto.NodeInfoVO;
import com.nexa.flowops.dto.NodeRegistryRequest;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.service.node.NodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final NodeService nodeService;
    private final NexaNodeMapper nodeMapper;
    private final SysUserMapper userMapper;

    // ==================== 在线节点（会话实时状态） ====================
    // 登录校验由 Sa-Token 拦截器统一完成（/api/nodes 未在白名单，未登录 401）；
    // 部署下拉框等业务场景：任何已登录用户可调用，授权由部署接口按项目权限把关

    @GetMapping
    public Result<List<NodeInfoVO>> listNodes() {
        return Result.ok(nodeService.getOnlineRunners());
    }

    @GetMapping("/{runnerId}")
    public Result<NodeInfoVO> getNode(@PathVariable String runnerId) {
        return nodeService.getRunnerStatus(runnerId)
                .map(Result::ok)
                .orElse(Result.fail("子节点不存在: " + runnerId));
    }

    // ==================== 节点登记管理（仅超级管理员，注册表 nexa_node） ====================
    // token 明文传入，服务端自动 sha256 存储，无需手工加密/插库。

    /** 查看全部已登记节点 */
    @GetMapping("/registry")
    public Result<List<NexaNodeVO>> listRegistry() {
        if (!isSuperAdmin()) {
            return Result.fail(403, "仅超级管理员可管理节点登记");
        }
        return Result.ok(nodeMapper.selectList(null).stream().map(this::toVO).toList());
    }

    /** 新增节点登记（runnerId + token 必填） */
    @PostMapping("/registry")
    public Result<Void> createRegistry(@RequestBody NodeRegistryRequest req) {
        if (!isSuperAdmin()) {
            return Result.fail(403, "仅超级管理员可管理节点登记");
        }
        if (req == null || req.getRunnerId() == null || req.getRunnerId().isBlank()) {
            return Result.fail("runnerId 不能为空");
        }
        if (req.getToken() == null || req.getToken().isBlank()) {
            return Result.fail("token 不能为空");
        }
        String runnerId = req.getRunnerId().trim();
        if (nodeMapper.selectById(runnerId) != null) {
            return Result.fail("节点已登记: " + runnerId + "，如需修改请调用更新接口");
        }
        NexaNode node = new NexaNode();
        node.setRunnerId(runnerId);
        node.setNodeName(req.getNodeName());
        node.setToken(DigestUtil.sha256Hex(req.getToken().trim()));
        node.setStatus("offline");
        nodeMapper.insert(node);
        return Result.ok("节点已登记");
    }

    /** 更新节点登记（nodeName / token 可选，传了才改） */
    @PutMapping("/registry/{runnerId}")
    public Result<Void> updateRegistry(@PathVariable String runnerId, @RequestBody NodeRegistryRequest req) {
        if (!isSuperAdmin()) {
            return Result.fail(403, "仅超级管理员可管理节点登记");
        }
        NexaNode node = nodeMapper.selectById(runnerId);
        if (node == null) {
            return Result.fail("节点未登记: " + runnerId);
        }
        if (req != null) {
            if (req.getNodeName() != null && !req.getNodeName().isBlank()) {
                node.setNodeName(req.getNodeName());
            }
            if (req.getToken() != null && !req.getToken().isBlank()) {
                node.setToken(DigestUtil.sha256Hex(req.getToken().trim()));
            }
        }
        nodeMapper.updateById(node);
        return Result.ok("节点登记已更新");
    }

    /** 删除节点登记 */
    @DeleteMapping("/registry/{runnerId}")
    public Result<Void> deleteRegistry(@PathVariable String runnerId) {
        if (!isSuperAdmin()) {
            return Result.fail(403, "仅超级管理员可管理节点登记");
        }
        if (nodeMapper.selectById(runnerId) == null) {
            return Result.fail("节点未登记: " + runnerId);
        }
        nodeMapper.deleteById(runnerId);
        return Result.ok("节点登记已删除");
    }

    private NexaNodeVO toVO(NexaNode node) {
        NexaNodeVO vo = new NexaNodeVO();
        vo.setRunnerId(node.getRunnerId());
        vo.setNodeName(node.getNodeName());
        // 在线状态以实时会话为准：主节点重启、会话被顶替、进程异常退出后
        // nexa_node.status 快照可能滞后，不能直接当作当前状态展示
        vo.setStatus(nodeService.isOnline(node.getRunnerId()) ? "online" : "offline");
        vo.setLastHeartbeat(node.getLastHeartbeat());
        vo.setCreateTime(node.getCreateTime());
        vo.setHasToken(node.getToken() != null && !node.getToken().isBlank());
        return vo;
    }

    private boolean isSuperAdmin() {
        String username;
        try {
            username = StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            return false;
        }
        SysUser user = userMapper.selectByUsername(username);
        return user != null && user.getIsSuperAdmin() == 1;
    }
}
