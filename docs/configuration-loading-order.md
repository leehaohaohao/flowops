# 配置加载顺序（单一解析链）

本文件是 FlowOps 后端配置来源的**唯一权威说明**。目标：每个配置项的取值都可以预先推断，
并且**逐变量降级**——上层没有这个键时，自动到下一层去找，不存在"某个来源整体盖掉另一个来源"。

## 1. 优先级链（高 → 低）

| 优先级 | 来源 | 示例 | 说明 |
| --- | --- | --- | --- |
| 1 | 命令行参数 | `java -jar app.jar --server.port=9090` | 一次性覆盖，最高优先级 |
| 2 | JVM 系统属性 | `-Dserver.port=9090`、`JAVA_OPTS` | 启动脚本 / IDE 运行配置 |
| 3 | 进程环境变量 | `docker run -e KEY=value`、`--env-file`、shell `export` | 部署脚本注入层 |
| 4 | `.env.<profile>`（缺失时降级 `.env`） | `flowops-app/.env.local` | 由 `DotenvPostProcessor` 作为属性源加载 |
| 5 | `application-<profile>.yml` | `application-prod.yml` | Config Data |
| 6 | `application.yml` 及代码内默认值 | `${NEXA_MASTER_PORT:8081}` 里的 `8081` | 兜底 |

解析规则：**每个变量独立**沿该链自上而下查找，命中即止；因此同一次启动里不同变量可以来自不同层。
例如 `DB_URL` 来自环境变量、`server.port` 来自 `application.yml`、`app.storage.path` 用代码默认值，三者互不影响。

## 2. profile 自身的解析顺序

profile 决定读取哪个 `.env.<profile>` 文件，其解析顺序与上面的链一致：

1. 命令行参数 `--spring.profiles.active=local`
2. JVM 系统属性 `-Dspring.profiles.active=local`
3. 进程环境变量 `SPRING_PROFILES_ACTIVE=local`
4. 兜底 `prod`（与 `Dockerfile` 的 `ENTRYPOINT` 默认值一致）

多 profile 形如 `prod,metrics` 时，取第一个（`prod`）作为 dotenv 文件名后缀。
`.env.<profile>` 不存在时降级尝试 `.env`；两者都不存在则跳过，配置全部来自环境变量与 `application*.yml`（不报错）。

## 3. 容器内的实际链路（`deploy-prod.sh`）

| 配置项 | 来源 | 默认值 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 脚本 `-e` | `prod` |
| `NEXA_MASTER_HOST` | 脚本 `-e` | `0.0.0.0` |
| `NEXA_MASTER_PORT` | 脚本 `-e` | `8081` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `JWT_SECRET` | `--env-file .env.<profile>`（同一文件也挂载到 `/app/.env.<profile>`，供应用读取） | 由运维填写 |
| `server.port` | `application.yml` | `8080`（容器内），宿主机发布为 `PORT`，默认 `8880` |

## 4. 实现位置

- `flowops-app/src/main/java/com/nexa/flowops/config/DotenvPostProcessor.java`
  唯一的 dotenv 加载器：`EnvironmentPostProcessor`，order `HIGHEST_PRECEDENCE + 5`，
  用 `addLast` 追加一个 `MapPropertySource`。该 order 保证它晚于
  `StandardEnvironment` 装配 `systemProperties` / `systemEnvironment`（所以低于 -D 与 -e），
  又早于加载 yml 的 `ConfigDataEnvironmentPostProcessor`（`HIGHEST_PRECEDENCE + 10`，所以高于 `application*.yml`）。
- `flowops-app/src/main/java/com/nexa/flowops/FlowopsApplication.java`
  `main` 不再自行加载 dotenv。**不要**再在 `main` 里用 `System.setProperty` 注入文件值：
  JVM 系统属性优先级高于进程环境变量，那样会把 `.env.<profile>` 抬到 `-e` 之上，使脚本注入的
  `NEXA_MASTER_HOST` / `NEXA_MASTER_PORT` 被文件里的同名键反向覆盖（容器端口与 `docker -p` 映射不一致，子节点将连不上）。
  2026-09 及更早的实现即如此，现已移除。
- 回归测试：`flowops-app/src/test/java/com/nexa/flowops/config/DotenvLoadingOrderTest.java`，
  配套资源 `flowops-app/src/test/resources/application-itprobe.yml`。
- 启动来源表：`flowops-app/src/main/java/com/nexa/flowops/config/ConfigSourceReporter.java`（生成表格）
  与 `ConfigSourceReportListener.java`（挂在 `ApplicationPreparedEvent` 打印），
  在 `FlowopsApplication.main` 里用 `application.addListeners(...)` 注册；
  回归测试 `flowops-app/src/test/java/com/nexa/flowops/config/ConfigSourceReporterTest.java`。

## 5. 如何验证

```bash
./mvnw test -pl flowops-app -Dtest=DotenvLoadingOrderTest '-Dsurefire.failIfNoSpecifiedTests=false'
```

该测试断言：

- 逐变量降级：`from.dotenv`（文件 + yml 都有）取文件值，`from.yml`（仅 yml 有）取 yml 值；
- `--from.arg` 覆盖文件值，`-Dfrom.sysprop` 覆盖文件值；
- 用**程序参数**传 profile（`--spring.profiles.active=itprobe`）能正确选中 `.env.itprobe`（历史缺陷：只认系统属性/环境变量，因而退回 `prod`）；
- profile 文件缺失时静默跳过，`application.yml` 取值不受影响；
- 属性源顺序 `commandLineArgs < systemProperties < systemEnvironment < dotenv[.env.<profile>] < application-<profile>.yml`，且 dotenv 源只出现一次。

进程环境变量无法在测试进程内设置，因此对它不做取值断言，而以属性源顺序等价证明。

## 6. 启动日志：配置来源表

每次启动都会打印一张表，说明本文档第 1 节的链**实际**是怎么生效的。它挂在 `ApplicationPreparedEvent`：
此时 `application*.yml` 与 `.env.<profile>` 已全部装载、上下文尚未刷新，因此**即使随后数据库连不上、启动中止，
这张表也已经打印出来了**，正好用于排查"参数从哪儿来的 / 必填变量是否注入"。

真实运行样例（`--spring.profiles.active=local`，本地 JDBC URL 已截断）：

```
==================== 配置来源（启动解析） ====================
解析顺序（高→低）：命令行参数 > -D 系统属性 > 环境变量(-e/--env-file) > .env.<profile> > application-<profile>.yml > application.yml
  键                                     定义来源                  占位符填充来源              生效值
  spring.profiles.active                 命令行参数                —                          local
  server.port                            application.yml           —                          8080
  spring.datasource.url                  application-local.yml     .env.local[DB_URL]         jdbc:mysql://db:3306/flowops?...
  spring.datasource.username             application-local.yml     .env.local[DB_USERNAME]    developer
  spring.datasource.password             application-local.yml     .env.local[DB_PASSWORD]    ********（敏感，值已隐藏）
  spring.datasource.driver-class-name    application-local.yml     —                          com.mysql.cj.jdbc.Driver
  sa-token.token-name                    application.yml           —                          Authorization
  sa-token.jwt-secret-key                application.yml           .env.local[JWT_SECRET]     ********（敏感，值已隐藏）
  nexa.master.enabled                    application.yml           —                          true
  nexa.master.host                       application.yml           默认值(127.0.0.1)          127.0.0.1
  nexa.master.port                       application.yml           默认值(8081)               8081
  nexa.master.heartbeat-timeout          application.yml           —                          30s
  docker.host                            application.yml           —                          tcp://localhost:2375
  app.storage.path                       application.yml           —                          /data/flowops/services
  app.logs.path                          application.yml           —                          /data/flowops/logs
============================================================
```

| 列 | 含义 |
| --- | --- |
| 键 | 被跟踪的配置项，清单在 `ConfigSourceReporter.WATCHED`，按需增删 |
| 定义来源 | 该键写在哪个来源（链上第一个命中的属性源）；`无（未在任何来源中定义）` 表示没有任何来源提供它 |
| 占位符填充来源 | 值形如 `${DB_URL}` 时，真正提供值的来源，如 `.env.local[DB_URL]`、`环境变量(-e/--env-file)[DB_URL]`；`默认值(8081)` 表示走占位符内联默认值；`X 未注入` 表示必填变量缺失 |
| 生效值 | 解析后的最终值；敏感项恒为 `********（敏感，值已隐藏）` |

规则：

- **敏感项只显示来源，不显示值**：`spring.datasource.password`、`sa-token.jwt-secret-key` 恒被掩码；
  值里内联的 `password=` / `pwd=` / `secret=` / `token=` 片段也会被抹掉（例如 JDBC URL 的查询串）。
- 必填占位符未注入时正常项显示 `<未解析：占位符 ${DB_URL} 未注入>`；敏感项显示掩码，缺口在"占位符填充来源"列体现。
  比等到启动失败再翻堆栈更快定位（容器里最常见的就是 `DB_URL` / `JWT_SECRET` 忘了注入）。
- 表格按**显示宽度**对齐（中文按两列计），不是按字符数。
- 关闭：`--flowops.config-report.enabled=false`（也可用环境变量或配置文件里的同名键）。

## 7. 实践约定

- 部署脚本用 `-e` 注入的键（`SPRING_PROFILES_ACTIVE`、`NEXA_MASTER_HOST`、`NEXA_MASTER_PORT`）不要重复写进 `.env.<profile>`：
  现在虽然 `-e` 会赢，但同一键写两处只会让排查变难。临时改端口请用脚本的 shell 变量（`PORT` / `NEXA_PORT` / `NEXA_MASTER_PORT`）。
- `.env.<profile>` 只放"应用自身需要的秘密配置"（数据库、`JWT_SECRET`）。
- `.env*` 文件与节点令牌不要提交、不要打进日志。
