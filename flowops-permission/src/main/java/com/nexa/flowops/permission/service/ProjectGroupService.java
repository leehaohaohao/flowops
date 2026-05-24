package com.nexa.flowops.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.permission.entity.ProjectGroup;
import com.nexa.flowops.permission.entity.Project;
import com.nexa.flowops.permission.entity.GroupMember;
import com.nexa.flowops.permission.mapper.ProjectGroupMapper;
import com.nexa.flowops.permission.mapper.ProjectMapper;
import com.nexa.flowops.permission.mapper.GroupMemberMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectGroupService {

    private final ProjectGroupMapper groupMapper;
    private final ProjectMapper projectMapper;
    private final GroupMemberMapper groupMemberMapper;

    public ProjectGroupService(ProjectGroupMapper groupMapper,
                               ProjectMapper projectMapper,
                               GroupMemberMapper groupMemberMapper) {
        this.groupMapper = groupMapper;
        this.projectMapper = projectMapper;
        this.groupMemberMapper = groupMemberMapper;
    }

    public ProjectGroup create(String name, String description) {
        ProjectGroup group = new ProjectGroup();
        group.setName(name);
        group.setDescription(description);
        groupMapper.insert(group);
        return group;
    }

    public ProjectGroup getById(Long id) {
        return groupMapper.selectById(id);
    }

    public List<ProjectGroup> listAll() {
        return groupMapper.selectList(null);
    }

    public List<ProjectGroup> listByUserId(Long userId) {
        List<GroupMember> memberships = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId));
        List<Long> groupIds = memberships.stream().map(GroupMember::getGroupId).toList();
        if (groupIds.isEmpty()) return List.of();
        return groupMapper.selectList(
                new LambdaQueryWrapper<ProjectGroup>().in(ProjectGroup::getId, groupIds));
    }

    public void update(Long id, String name, String description) {
        ProjectGroup group = groupMapper.selectById(id);
        if (group == null) throw new BusinessException("项目组不存在");
        if (name != null) group.setName(name);
        if (description != null) group.setDescription(description);
        groupMapper.updateById(group);
    }

    public void delete(Long id) {
        // 检查是否有项目
        Long projectCount = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getGroupId, id));
        if (projectCount > 0) {
            throw new BusinessException("该项目组下仍有 " + projectCount + " 个项目，请先迁移或删除");
        }
        // 删除组成员
        groupMemberMapper.delete(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, id));
        groupMapper.deleteById(id);
    }

    public long getMemberCount(Long groupId) {
        return groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId));
    }

    public long getProjectCount(Long groupId) {
        return projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getGroupId, groupId));
    }
}
