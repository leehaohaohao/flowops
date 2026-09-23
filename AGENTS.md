# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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
- **dotenv-java**: `DotenvPostProcessor` loads `.env.{profile}` into Spring Environment before YAML parsing. Priority: system env vars > dotenv > yml.

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
- Uses `--env-file` for Docker env vars + `-v` mount for DotenvPostProcessor file reading
- Container exposes port 8080, mapped to host port 8880 by default

Artifact storage: `/data/flowops/services/{deployName}/` — JARs, dist dirs, generated docker-compose.yml
