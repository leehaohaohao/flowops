package com.nexa.flowops.service;

import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Map;

@Service
public class ServiceMgmtService {

    private final DeployServiceMapper serviceMapper;
    private final String storagePath = "/data/flowops/services";

    public ServiceMgmtService(DeployServiceMapper serviceMapper) {
        this.serviceMapper = serviceMapper;
    }

    public List<DeployService> list() {
        return serviceMapper.selectList(null);
    }

    public DeployService getById(Long id) {
        return serviceMapper.selectById(id);
    }

    public void createService(Map<String, Object> params) {
        DeployService service = new DeployService();
        service.setName((String) params.get("name"));
        service.setPort(Integer.parseInt(String.valueOf(params.get("port"))));
        service.setVolumeDir(storagePath + "/" + service.getName());
        service.setDockerfile((String) params.get("dockerfile"));
        service.setDockerCompose((String) params.get("dockerCompose"));
        service.setStatus("stopped");
        serviceMapper.insert(service);

        // 创建服务目录
        new File(service.getVolumeDir()).mkdirs();
    }

    public void updateService(Long id, Map<String, Object> params) {
        DeployService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new RuntimeException("服务不存在");
        }
        if (params.containsKey("name")) {
            service.setName((String) params.get("name"));
        }
        if (params.containsKey("port")) {
            service.setPort(Integer.parseInt(String.valueOf(params.get("port"))));
        }
        if (params.containsKey("dockerfile")) {
            service.setDockerfile((String) params.get("dockerfile"));
        }
        if (params.containsKey("dockerCompose")) {
            service.setDockerCompose((String) params.get("dockerCompose"));
        }
        serviceMapper.updateById(service);
    }

    public void deleteService(Long id) {
        serviceMapper.deleteById(id);
    }
}
