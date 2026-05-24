package com.nexa.flowops.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.permission.entity.Project;
import com.nexa.flowops.permission.mapper.ProjectMapper;
import com.nexa.flowops.permission.ExternalDataProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ExternalDataProvider externalDataProvider;

    public ProjectService(ProjectMapper projectMapper, ExternalDataProvider externalDataProvider) {
        this.projectMapper = projectMapper;
        this.externalDataProvider = externalDataProvider;
    }

    public Project create(Long groupId, String name, String description) {
        Project project = new Project();
        project.setGroupId(groupId);
        project.setName(name);
        project.setDescription(description);
        projectMapper.insert(project);
        return project;
    }

    public Project getById(Long id) {
        return projectMapper.selectById(id);
    }

    public List<Project> listByGroupId(Long groupId) {
        return projectMapper.selectList(
                new LambdaQueryWrapper<Project>().eq(Project::getGroupId, groupId));
    }

    public List<Project> listByGroupIds(List<Long> groupIds) {
        if (groupIds.isEmpty()) return List.of();
        return projectMapper.selectList(
                new LambdaQueryWrapper<Project>().in(Project::getGroupId, groupIds));
    }

    public List<Project> listByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return projectMapper.selectList(
                new LambdaQueryWrapper<Project>().in(Project::getId, ids));
    }

    public void update(Long id, String name, String description) {
        Project project = projectMapper.selectById(id);
        if (project == null) throw new BusinessException("项目不存在");
        if (name != null) project.setName(name);
        if (description != null) project.setDescription(description);
        projectMapper.updateById(project);
    }

    public void delete(Long id) {
        long serviceCount = externalDataProvider.countServicesByProjectId(id);
        if (serviceCount > 0) {
            throw new BusinessException("该项目下仍有 " + serviceCount + " 个服务，请先迁移或删除");
        }
        projectMapper.deleteById(id);
    }
}
