# FlowOps 项目全面 Code Review

> 日期: 2026-05-12
> 分支: 20260512-plan
> 版本: 1.1.0

## 项目概况

FlowOps 是一个自托管 CI/CD 部署平台，Spring Boot 3.3.0 单体架构，Java 17，Thymeleaf + Bootstrap 5 前端，通过 Docker CLI 管理容器。整体结构清晰，约 27 个 Java 文件，代码量适中。

---

## 严重问题 (Critical)

### 1. 明文存储密码
`AuthService.java` 和 `UserService.java` 都以明文比对/存储密码。代码中有注释承认这是技术债务。**生产环境必须使用 BCrypt 或类似方案。**

### 2. 敏感信息硬编码并提交到 Git
- `application-prod.yml`: MySQL 密码 `lihao123456` + 公网 IP `121.40.154.188`
- `application-dev.yml`: MySQL 密码 `123456`
- `application.yml`: JWT 密钥 `flowops2026SecretKeyForJwtSigning` 硬编码

**建议**: 使用环境变量 `${DB_PASSWORD}`、`jwt.secret` 等外部化配置，并将 `*.yml` 中的密码加入 `.gitignore`。

### 3. 路径遍历风险
`LogController` 的 `content` 端点接受任意 filename 参数，虽然拼接了 `logBasePath`，但没有显式的 `../` 路径遍历检查。攻击者可能读取系统任意文件。

### 4. 无登录暴力破解防护
`/auth/login` 无任何限流、验证码或账户锁定机制，攻击者可以无限尝试密码。

---

## 高危问题 (High)

### 5. 删除服务不清理磁盘文件
`ServiceMgmtService.deleteService()` 只删除数据库记录，`/data/flowops/services/{name}/` 目录及上传的 JAR/dist 残留在磁盘上，导致磁盘泄漏。

### 6. 部署接口阻塞请求线程最长 30 秒
`DeployExecutorService.deploy()` 中的 `waitForContainers()` 使用 `Thread.sleep` 轮询最多 30 秒，直接占用 HTTP 请求线程。高并发下会导致线程池耗尽。**建议**: 改为异步部署 + WebSocket/轮询通知。

### 7. 服务创建缺少输入校验
`ServiceMgmtService.createService()` 对 `port` 直接 `Integer.parseInt()` 无 null 检查、无端口范围校验（1-65535）、无服务名格式校验。非法输入会导致 500 错误或数据损坏。

### 8. WebSocket 功能是空壳
`WebSocketConfig.java` 注册了 `/ws/logs` 端点，但 `handleTextMessage()` 为空实现。如果前端尝试连接会得到无效响应。要么实现它，要么移除避免误导。

---

## 中等问题 (Medium)

### 9. 无 CSRF 防护
纯 JWT 方案下，如果 token 存在 localStorage 则 CSRF 风险较低，但代码中 `is-read-cookie: true` 开启了 cookie 读取，如果 token 被设置到 cookie 中则存在 CSRF 风险。

### 10. 错误处理不完善
`DeployExecutorService.deploy()` 的 catch 块将异常信息写入数据库记录，但没有将完整的异常堆栈写入日志文件，排查部署失败原因时信息不足。

### 11. 未使用的配置项
`docker.host: tcp://localhost:2375` 配置了但代码从不读取（全部用 CLI），应清理避免误导。

### 12. 测试覆盖几乎为零
只有一个空的 `contextLoads()` 冒烟测试。核心的部署引擎、认证逻辑、服务 CRUD 全部没有单元测试。

### 13. deploy.sh 硬编码 IP
部署脚本中 `121.40.154.188` 硬编码，应改为读取环境变量或参数。

---

## 低危 / 代码质量 (Low)

### 14. 前端 Authorization Header 非标准
`app.js` 发送 `Authorization: {token}` 而非标准的 `Authorization: Bearer {token}`。Sa-Token 能识别，但不符合行业规范，切换框架时会出问题。

### 15. 前端逻辑全部内联
每个 Thymeleaf 页面的 `<script>` 块包含大量业务逻辑，没有模块化。随着功能增长会变得难以维护。**建议**: 将页面级 JS 抽取到独立文件。

### 16. MyBatis-Plus 几乎没用到
3 个 Mapper 全部继承 `BaseMapper`，只有 `SysUserMapper` 有一个自定义 XML 查询。MyBatis-Plus 的很多能力（条件构造器、分页插件等）未使用。

### 17. logback-spring.xml 与配置冲突
`logback-spring.xml` 中设置 DEBUG 级别，但 `application-dev.yml` 也设置了 DEBUG，`application-prod.yml` 设置 INFO，存在重复配置。

---

## 架构建议

| 方面 | 当前 | 建议 |
|------|------|------|
| 密码 | 明文 | BCrypt (`spring-security-crypto` 可独立引入) |
| 部署 | 同步阻塞 | 异步执行 + 状态轮询/WebSocket |
| 配置 | 硬编码 | 环境变量 + `.env` 文件 |
| 测试 | 仅 contextLoads | 至少覆盖 Service 层单元测试 |
| 日志 | 仅控制台 | 生产环境加文件输出 + 日志归档 |
| Docker | ProcessBuilder CLI | 可用但建议加超时和错误码解析 |

---

## 总结

FlowOps 作为一个内部工具原型，功能完整度不错——支持三种服务类型（后端/前端/全栈），有预览配置、日志查看、用户管理等。主要风险集中在**安全层面**（明文密码、硬编码凭据、路径遍历、无限登录尝试）和**可靠性层面**（同步阻塞部署、无输入校验）。建议优先处理 Critical 级别的 4 个问题，再逐步补充测试和完善错误处理。
