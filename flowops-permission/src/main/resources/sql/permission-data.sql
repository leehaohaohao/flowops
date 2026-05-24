-- FlowOps 权限模块 - 种子数据
-- 集成方需在执行 permission-schema.sql 后执行此脚本

-- 权限定义
INSERT INTO perm_definition (code, description) VALUES
('VIEW', '查看服务列表和状态'),
('DEPLOY', '部署/启动服务'),
('START', '启动已停止的服务'),
('STOP', '停止运行中的服务'),
('UPLOAD', '上传 JAR/dist 文件'),
('EDIT_CONFIG', '编辑服务配置'),
('DELETE', '删除服务');

-- 预设角色
INSERT INTO perm_role (name, is_preset, description) VALUES
('viewer',     1, '只读权限'),
('operator',   1, '运维操作权限'),
('editor',     1, '编辑权限'),
('admin',      1, '项目管理员，全部权限'),
('supervisor', 1, '项目组主管，可管理成员和项目');

-- 角色-权限映射
-- viewer (id=1)
INSERT INTO role_permission (role_id, perm_code) VALUES (1, 'VIEW');

-- operator (id=2)
INSERT INTO role_permission (role_id, perm_code) VALUES
(2, 'VIEW'), (2, 'DEPLOY'), (2, 'START'), (2, 'STOP');

-- editor (id=3)
INSERT INTO role_permission (role_id, perm_code) VALUES
(3, 'VIEW'), (3, 'DEPLOY'), (3, 'START'), (3, 'STOP'), (3, 'UPLOAD'), (3, 'EDIT_CONFIG');

-- admin (id=4)
INSERT INTO role_permission (role_id, perm_code) VALUES
(4, 'VIEW'), (4, 'DEPLOY'), (4, 'START'), (4, 'STOP'), (4, 'UPLOAD'), (4, 'EDIT_CONFIG'), (4, 'DELETE');

-- supervisor (id=5) - 与 admin 相同的项目权限，外加管理能力
INSERT INTO role_permission (role_id, perm_code) VALUES
(5, 'VIEW'), (5, 'DEPLOY'), (5, 'START'), (5, 'STOP'), (5, 'UPLOAD'), (5, 'EDIT_CONFIG'), (5, 'DELETE'),
(5, 'MANAGE_MEMBERS'), (5, 'MANAGE_PROJECTS');
