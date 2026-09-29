# 待修复：节点管理授权统一到现有权限体系

> 状态：**待实施**。前置：[阶段 1 SSH 连接验证](../2026-09-27-node-ssh-connection-plan.md)的 H1 与 S4 真实验收完成。SSH 主机身份问题未关闭前，不穿插本项权限代码改动。本文件只记录修复计划，当前没有修改授权实现。

## 问题与决定

当前 `NodeAdminGuard` 在 `flowops-app` 中再次读取 `sys_user.is_super_admin`，供 `NodeController`、`NodeSshController`、`NodePackageController` 手写 `if (!isSuperAdmin())` 使用。现有 `flowops-permission` 已通过 `StpInterfaceImpl` 把同一数据库标记映射为 Sa-Token 的 `super_admin` 角色，`UserController`、`ProjectController`、`PermissionController` 已使用 `@SaCheckRole("super_admin")`。`GlobalExceptionHandler` 已将角色不足转换为统一响应 `code=403`。因此 `NodeAdminGuard` 没有独立授权语义，只增加重复查询和两套实现不一致的维护风险。

修复采用现有 Sa-Token **全局超级管理员角色**。`NodeController` 仅对节点登记的管理方法逐一加角色注解，保留 `GET /api/nodes` 和 `GET /api/nodes/{runnerId}` 的已登录用户访问；`NodeSshController`、`NodePackageController` 的全部方法可在类级加角色注解。确认拦截和 401/403 响应后删除 `NodeAdminGuard` 及其专用测试。前端仍只控制入口显隐，后端角色检查是最终边界。

不得直接用项目级 `@RequirePermission("EDIT_CONFIG")`、`DEPLOY` 等替代：当前 `StpInterfaceImpl.getPermissionList()` 合并用户在所有项目的权限，项目 A 的授权不能自动扩展为所有节点的全局管理权。若日后要向非超管开放部分节点操作，须另定全局节点权限模型、作用域和 API 契约，本次不实施。

| 仓库绝对路径 | 负责人 | 本项范围 |
| --- | --- | --- |
| `D:\project\backend\flowops` | 协调者复核角色契约；后端实施和验证 | 节点相关控制器、`NodeAdminGuard`、对应权限测试与本文档 |
| `D:\project\front\flowops-front` | 无需改动，除非后端统一响应导致现有节点页测试失败 | 只读核对 403 处理与超管显隐 |
| `D:\project\go\flowops-executor`、`D:\project\mix\nexa-protocol` | 无 | 不修改 |

## 开发顺序表

| 顺序 | 项目 | 具体任务 | 前置行 | 允许文件 / 模块 | 交付物 | 验证 | 明确不做 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A0 | `D:\project\backend\flowops`（协调者） | SSH H1/S4 完成后核对节点管理接口清单、现有 `super_admin` 角色映射和统一 401/403 响应，定稿注解应用位置 | SSH H1、S4 真实验收 | 本文件、只读检查权限模块和节点控制器 | 不影响在线节点读取的明确授权清单 | 对照 `StpInterfaceImpl`、`SaTokenConfig`、`GlobalExceptionHandler` 与现有 API | 不修改代码，不扩大节点管理角色 |
| A1 | `D:\project\backend\flowops` | 将登记方法改用方法级 `@SaCheckRole("super_admin")`，SSH/发布包控制器改用类级角色注解；移除手写校验、`NodeAdminGuard` 和专用测试，更新控制器测试与接口文档中的 403 文案 | A0 | `flowops-app` 的 `NodeController`、`NodeSshController`、`NodePackageController`、`NodeAdminGuard` 及节点权限测试；`docs/frontend-api/node-api.md`、`docs/frontend-api/node-package-api.md`、本文档 | 仅一条超管角色判定路径 | 未登录 401、非超管 403、超管可用；普通已登录用户仍可读在线节点；发布包上传/分发与 SSH 测试不得绕过授权；接口说明与实际响应一致 | 不改 `flowops-permission` 的项目权限计算，不修改 SSH/分发业务逻辑 |
| A2 | `D:\project\backend\flowops`（协调者验收） | 复核 A1 的 API 结果码与前端入口显隐，记录接口覆盖范围和测试结果 | A1 | 本文件、节点权限集成测试；前端只读检查 | 权限替换完成记录 | 特别核对仅有某项目 `EDIT_CONFIG`/`DEPLOY` 的用户不能操作任何全局节点管理接口 | 不引入新的全局权限码，不改前端页面或其他仓库 |

注意：`@SaCheckRole` 的默认 403 文案可能与原控制器的“仅超级管理员可管理…”不同；本次保持业务响应码 `403`，更新依赖旧文案的测试和接口说明，不用保留重复 Guard 只为维持文案。计划完成状态以代码、测试和 SSH 前置验收为准。
