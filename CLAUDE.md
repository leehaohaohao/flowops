# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build/Run/Test

```bash
./mvnw compile              # Compile (Maven wrapper)
./mvnw test                 # Run all tests (currently only contextLoads smoke test)
./mvnw spring-boot:run      # Run locally on port 8080
./mvnw package -DskipTests  # Build fat JAR
```

## Architecture

FlowOps is a self-hosted CI/CD deployment platform — a single-module Spring Boot 3.3.0 monolith (Java 17, Maven) with a Thymeleaf server-rendered UI (Bootstrap 5 CDN, vanilla JS).

**Package layout** (`com.nexa.flowops`):
- `controller/` — REST APIs (`/api/*`) + `PageController` for Thymeleaf page routes
- `service/` — Business logic; `DeployExecutorService` is the core deployment engine
- `entity/` + `mapper/` — MyBatis-Plus data layer (MySQL, three tables)
- `config/` — SaTokenConfig (auth interceptor), TraceFilter (MDC traceId), WebSocketConfig
- `common/Result.java` — Unified API response wrapper `{code, msg, data}` used by all controllers

**Key design decisions:**
- **Docker CLI, not SDK**: The original `docker-java` SDK dependency was removed. All container operations use `ProcessBuilder` to invoke `docker`/`docker-compose` CLI directly. See `DeployExecutorService` and `DockerUtil`.
- **Auth**: Sa-Token with JWT tokens (`token-style: jwt`), cookies enabled (`is-read-cookie: true`). Excluded paths: `/auth/login`, `/auth/captcha`, `/error`.
- **Passwords stored in plaintext** — acknowledged tech debt, code comments say "生产环境应使用 BCrypt".
- **WebSocket at `/ws/logs`** is configured but the handler is a stub (empty `TextMessageHandler`).
- **Artifact storage**: `/data/flowops/services/{service-name}/` — JARs, dist directories, and generated docker-compose.yml files live here.

**Database**: MySQL `flowops` database on `localhost:3306` (root/123456). Tables: `sys_user`, `deploy_service`, `deploy_record`. Init script at `src/main/resources/sql/init.sql`. Default admin: `admin/admin123`.

**Frontend auth flow**: Token stored in `localStorage`, sent via `Authorization` header on API calls. JS helper: `api()` in `static/js/app.js`.
