package com.nexa.flowops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
        String name = (String) params.get("name");
        // 检查服务名是否已存在
        Long count = serviceMapper.selectCount(
                new LambdaQueryWrapper<DeployService>().eq(DeployService::getName, name));
        if (count > 0) {
            throw new RuntimeException("服务名「" + name + "」已存在，请更换名称");
        }

        DeployService service = new DeployService();
        service.setName(name);
        service.setPort(Integer.parseInt(String.valueOf(params.get("port"))));
        service.setServiceType((String) params.get("serviceType"));
        service.setServiceConfig((String) params.get("serviceConfig"));
        service.setVolumeDir(storagePath + "/" + name);
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
        if (params.containsKey("serviceType")) {
            service.setServiceType((String) params.get("serviceType"));
        }
        if (params.containsKey("serviceConfig")) {
            service.setServiceConfig((String) params.get("serviceConfig"));
        }
        serviceMapper.updateById(service);
    }

    public void deleteService(Long id) {
        serviceMapper.deleteById(id);
    }
}
