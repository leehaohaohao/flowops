package com.nexa.flowops.dto;

import com.nexa.flowops.permission.dto.ProjectBriefVO;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UserInfoVO {
    private String username;
    private boolean isSuperAdmin;
    private List<ProjectBriefVO> projects;
    private Map<Long, List<String>> projectPermissions;
}
