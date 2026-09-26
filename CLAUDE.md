# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build/Run/Test

```bash
./mvnw compile              # Compile all modules
./mvnw test                 # Run tests (currently only contextLoads smoke test)
./mvnw spring-boot:run -pl flowops-app   # Run locally on port 8080
./mvnw package -DskipTests  # Build fat JAR (output: flowops-app/target/)
```

Frontend is in a separate repo at `D:\project\front\flowops-front` (React + Vite + Ant Design 6):
```bash
npm run dev        # Dev server (proxies to localhost:8080)
npm run build      # Prod build (outputs to backend static resources)
npm run build:vm   # VM build (API points to 192.168.48.129:8880)
```

## Architecture

FlowOps is a self-hosted CI/CD deployment platform. Multi-module Maven project (Java 17, Spring Boot 3.3.0).

### Modules

- **flowops-common** — `Result` response wrapper, `BusinessException`, `PasswordUtil` (BCrypt), permission annotations (`@RequirePermission`, `@RequireProjectSupervisor`)
- **flowops-permission** — Auth (Sa-Token JWT), RBAC with projects/roles/members. `ExternalDataProvider` SPI interface for cross-module queries
- **flowops-app** — Main application: deploy engine, service CRUD, log system, WebSocket handlers. Implements `ExternalDataProvider` via `FlowOpsExternalDataProvider`

### Key Design Decisions

- **Docker CLI, not SDK**: All container ops use `ProcessBuilder` to invoke `docker`/`docker-compose` CLI via `DockerUtil`. No docker-java dependency.
- **Config generation chain**: `ConfigGenerator` interface + `ConfigGeneratorChain` + Spring `@Order`. Generators: `DockerfileGenerator` → `NginxConfGenerator` → `ComposeYmlGenerator`. Called by `DeployExecutorService` before deploy.
- **Auth**: Sa-Token with JWT stateless mode. Excluded paths: `/auth/**`, static assets, `/error`. Permission enforced via `PermissionAspect` (AOP) + `PermissionInterceptor` (URL patterns).
- **Cross-module SPI**: Permission module defines `ExternalDataProvider` interface; app module provides `FlowOpsExternalDataProvider` implementation. No compile-time dependency from permission → app.
- **Log system**: `LogSource` SPI with `LocalDockerLogSource` implementation. Logs stored at `{app.logs.path}/{projectId}/{serviceId}/{type}/{date}/`. WebSocket endpoints: `/ws/logs` (file tailing, 2s polling) and `/ws/container-logs` (live `docker compose logs --follow`).
- **配置加载顺序**: 单一解析链，高 → 低：命令行参数 > `-D` 系统属性 > 进程环境变量（`docker run -e` / `--env-file`）> `.env.<profile>`（缺失降级 `.env`）> `application-<profile>.yml` > `application.yml` 与代码默认值。**逐变量降级**：每个键独立沿链查找，命中即止，上层没有就自动落下一层。唯一 loader 是 `DotenvPostProcessor`（order `HIGHEST_PRECEDENCE + 5`、`addLast`），它自行解析 profile（`--spring.profiles.active` → `-Dspring.profiles.active` → `SPRING_PROFILES_ACTIVE` → `prod`），因为该 order 下 `environment.getActiveProfiles()` 在 Spring Boot 3.3 里仍为空。禁止在 `main` 里用 `System.setProperty` 注入 dotenv 值：系统属性高于环境变量，会让挂载的 `.env.<profile>` 反向盖掉 `-e`。详见 `docs/configuration-loading-order.md`。启动时会打印"配置来源表"（键 / 定义来源 / 占位符填充来源 / 生效值，`ConfigSourceReporter`）：敏感项（`spring.datasource.password`、`sa-token.jwt-secret-key`）只显示来源不显示值，必填占位符未注入会直接标出缺口；`flowops.config-report.enabled=false` 可关闭。

### REST API Routes

- `/auth/**` — Login/logout/info (`AuthController`)
- `/api/services` — Service CRUD (`ServiceController`)
- `/api/deploy/**` — Upload, start, stop, restart, remove, status, logs (`DeployController`)
- `/api/logs/**` — Log file listing and content (`LogController`)
- `/api/stats/dashboard` — Dashboard stats (`StatsController`)
- `/api/users`, `/api/projects`, `/api/projects/{id}/members`, `/api/roles`, `/api/permissions` — Permission module controllers

### Database

MySQL `flowops` database. Init scripts:
- App tables: `flowops-app/src/main/resources/sql/init.sql` (deploy_service, deploy_record)
- Permission tables: `flowops-permission/src/main/resources/sql/permission-schema.sql` + `permission-data.sql`
- Migrations: `V2_0_0__add_extra_ports.sql`, `V2_1_0__add_port_mappings.sql`, `V2_2_0__add_deploy_name.sql`

Default admin: `admin/admin123` (BCrypt hashed).

### Configuration Profiles

- `dev` — localhost MySQL, DEBUG logging
- `prod` — DB from env vars, INFO logging
- `local` — DB from env vars, DEBUG logging (for VM deployment)

Environment secrets via `.env` files: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`.

### Deployment

Deploy script: `deploy-prod.sh <jar> [profile] [env-file]`
- `./deploy-prod.sh app.jar` — prod profile, `.env.prod`
- `./deploy-prod.sh app.jar local .env.local` — local profile, `.env.local`
- Uses `--env-file` for Docker env vars + `-v` mount of the same file to `/app/<name>` for the app's dotenv loading
- Container exposes port 8080, mapped to host port 8880 by default (`PORT` 可覆盖)
- **主从通信**：脚本同时发布 Nexa Protocol Master 端口（容器与宿主机默认均为 8081，与 `application.yml` 一致；
  `NEXA_MASTER_PORT` 覆盖容器内监听端口、`NEXA_PORT` 覆盖宿主机发布端口），
  并让容器内监听 `NEXA_MASTER_HOST=0.0.0.0`（shell 变量可覆盖），供其他机器上的子节点连接；
  子节点接入前需放通该端口并用超管录入节点令牌（`POST /api/nodes/registry`）
- 这三个键由脚本 `-e` 注入，优先级高于 `.env.<profile>` 文件，因此无需写进 env 文件；临时改端口用 shell 变量（`PORT` / `NEXA_PORT` / `NEXA_MASTER_PORT`）覆盖

Artifact storage: `/data/flowops/services/{deployName}/` — JARs, dist dirs, generated docker-compose.yml
