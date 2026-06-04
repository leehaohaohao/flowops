package com.nexa.flowops.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.base.BusinessException;
import com.nexa.flowops.permission.entity.GroupMember;
import com.nexa.flowops.permission.entity.Project;
import com.nexa.flowops.permission.mapper.GroupMemberMapper;
import com.nexa.flowops.permission.mapper.ProjectMapper;
import com.nexa.flowops.permission.ExternalDataProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final ExternalDataProvider externalDataProvider;

    public ProjectService(ProjectMapper projectMapper,
                          GroupMemberMapper groupMemberMapper,
                          ExternalDataProvider externalDataProvider) {
        this.projectMapper = projectMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.externalDataProvider = externalDataProvider;
    }

    public Project create(String name, String description) {
        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        projectMapper.insert(project);
        return project;
    }

    public Project getById(Long id) {
        return projectMapper.selectById(id);
    }

    public List<Project> listAll() {
        return projectMapper.selectList(null);
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
        Project project = projectMapper.selectById(id);
        if (project != null && project.getIsDefault() != null && project.getIsDefault() == 1) {
            throw new BusinessException("默认项目不可删除");
        }
        long serviceCount = externalDataProvider.countServicesByProjectId(id);
        if (serviceCount > 0) {
            throw new BusinessException("该项目下仍有 " + serviceCount + " 个服务，请先迁移或删除");
        }
        // 删除项目成员
        groupMemberMapper.delete(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getProjectId, id));
        projectMapper.deleteById(id);
    }

    public Project getDefaultProject() {
        return projectMapper.selectOne(
                new LambdaQueryWrapper<Project>().eq(Project::getIsDefault, 1));
    }

    public long getMemberCount(Long projectId) {
        return groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getProjectId, projectId));
    }

    public long getServiceCount(Long projectId) {
        return externalDataProvider.countServicesByProjectId(projectId);
    }
}
