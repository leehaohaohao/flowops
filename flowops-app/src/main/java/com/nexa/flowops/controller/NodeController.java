package com.nexa.flowops.controller;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.NodeInfoVO;
import com.nexa.flowops.service.NodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final NodeService nodeService;

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
}
