# AGENTS.md

Guidance for agents working in this repository. Check code and tests before treating a dated plan as current status.

## Build, Run, Test

```bash
./mvnw compile                                  # Compile all four modules
./mvnw test                                     # Run the test suite
./mvnw test -pl flowops-app -am -Dtest=ArtifactTransferLinkTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw spring-boot:run -pl flowops-app           # Local app, port 8080
./mvnw package -DskipTests                      # App JAR in flowops-app/target/
```

On Windows use `mvnw.cmd` in place of `./mvnw`; in PowerShell, quote `'-Dsurefire.failIfNoSpecifiedTests=false'`. `FlowopsApplicationTests` requires application/database configuration; `ArtifactTransferLinkTest` starts an in-process protocol master with mocked mappers and does not require a real Go runner or MySQL.

Related repositories:

- Frontend: `D:\project\front\flowops-front` (React, TypeScript, Vite, Ant Design). `npm run dev` proxies `/auth`, `/api`, and `/ws` to the backend; `npm run build` and `npm run build:vm` use different environment modes. Check `vite.config.ts` and `.env.*` before assuming the build output path.
- Runner: `D:\project\go\flowops-executor` (`go build ./...`). Its `go.mod` currently replaces `github.com/leehaohaohao/nexa-protocol/go` with the local protocol checkout.
- Protocol: `D:\project\mix\nexa-protocol` (shared Protobuf contracts, Java master SDK, Go client SDK). The app depends on Java `com.nexa:nexa-protocol:0.6.2`; the runner uses Go protocol through the local replace. From v0.6.0 registration is authenticate-before-takeover and the rejection response is flushed before closing, so listener code must not close connections or touch the session table on rejection. From v0.6.1 every registered message is authorized by the session bound to the sending connection. From v0.6.2 disconnect events follow "whoever successfully removes the current session notifies exactly once" (reason `heartbeat_timeout` when the session was marked timed out, else `connection_lost`). Because a new session can still register between that removal and the callback, `FlowOpsMasterListener` decides attribution by the protocol session registry (a session still registered at callback time must be the successor, so cleanup is skipped) inside a per-runner lock, fails pending tasks/queries only when their recorded session generation matches the event's generation, and re-checks the registry after writing the offline snapshot to repair it. Do not compare `SessionTracker` generation labels to decide attribution: the backend's `onRegister` callback runs before the protocol writes the session into the registry, so label order can differ from the effective session order.

## Architecture

FlowOps is a self-hosted CI/CD deployment platform (Java 17, Spring Boot 3.3.0, MySQL, MyBatis-Plus). It is evolving from local Docker deployment to a master/runner design.

- `flowops-common`: response/errors, password and digest helpers, permission annotations.
- `flowops-docker`: `DockerClient` / `DefaultDockerClient` abstraction. It executes Docker CLI and Docker Compose through `ProcessBuilder`, not a Docker SDK.
- `flowops-permission`: Sa-Token JWT authentication, project/member/role/permission RBAC, and the `ExternalDataProvider` SPI.
- `flowops-app`: REST API, service/deployment orchestration, generated Docker config, artifact registry/transfer, node management, logs, WebSockets, and `FlowOpsExternalDataProvider` implementation.

### Deployment and node flow

- `DeployExecutorService` routes operations by `DeployService.nodeId`. Blank means local; a specific runner ID means remote; `auto` selects an online runner with the fewest reported running tasks. Check each operation's fallback behavior in code rather than assuming it is uniform.
- Local operations use `LocalDeployRunner` and `DockerClient`. `ConfigGeneratorChain` runs `DockerfileGenerator`, `NginxConfGenerator`, and `ComposeYmlGenerator` in Spring order.
- Remote operations use `RemoteDeployDispatcher` to generate config, send `TaskRequest` through Nexa Protocol, and register a pending task. `RemoteTaskManager` records the asynchronous result, timeout, or node disconnect.
- The Java `NexaMaster` accepts Go runner connections. `FlowOpsMasterListener` handles registration, heartbeats, disconnects, task/query responses, and artifact requests. `NodeService` tracks live sessions/load; `SessionTracker` gives each registration a unique generation label (diagnostics only) and provides the per-runner lock used to serialize registration/disconnect handling; disconnect attribution itself uses the protocol session registry; `QueryManager` sends remote status/log requests and waits for responses.
- `deploy_artifact` stores metadata/version/checksum pointing to files under the existing service volume directory. `ArtifactTransferManager` serves artifact chunks over Nexa Protocol; the runner requests, verifies SHA-256, saves/extracts, then runs Docker Compose. The former HTTP artifact download endpoint is removed.
- Node registration uses a per-runner token stored as SHA-256 in `nexa_node`; `/api/nodes/registry` manages entries for super administrators. After registration, task responses and artifact requests use the bound session identity for authorization. Inspect each handler when changing authentication.
- The log system uses `LogSource` / `LocalDockerLogSource`, REST log APIs, `/ws/logs` file tailing, and `/ws/container-logs` container logs. Remote container status/logs use protocol queries.

### Current distributed-development status

The latest relevant design is `docs/2026-08-25-artifact-standardization-node-auth-plan.md`; `docs/2026-08-11-remote-deploy-enhancement-plan.md` covers status/log queries and frontend node selection; `docs/2026-09-23-runner-connection-recovery-plan.md` covers reconnect handling. Their dated implementation-status headers lag behind the current source: protocol v0.6.2, backend artifact registry/transfer, node authentication, and reconnect-event handling, and Go runner token/artifact handling are all present in code. The frontend also contains node selection and node management work; inspect its working tree because some changes may be uncommitted.

Verification is narrower than implementation. `ArtifactTransferLinkTest` checks Java master-to-simulated-Java-client file transfer with mocked persistence. It does not prove a real Go runner, MySQL migrations, artifact upload, Docker build/start, status/logs, and UI work together. Treat end-to-end distributed deployment and L1/L2 authorization as awaiting real integration verification unless newer test or deployment evidence is available. Reconnect/retry and message replay are outside the 2026-08-25 plan.

## API and Storage

- `/auth/**`: login/logout/user info.
- `/api/services/**`, `/api/deploy/**`: service CRUD, uploads, lifecycle and status.
- `/api/nodes`, `/api/nodes/{runnerId}`: live node information; `/api/nodes/registry/**`: node registration management.
- `/api/logs/**`, `/api/stats/dashboard`: logs and dashboard.
- `/api/users`, `/api/projects`, `/api/roles`, `/api/permissions`: permission module APIs.
- App schema: `flowops-app/src/main/resources/sql/init.sql`; permission schema/data: `flowops-permission/src/main/resources/sql/`. App migrations include `V3_0_0__add_node_id.sql`, `V3_1_0__create_deploy_artifact.sql`, and `V3_1_1__create_nexa_node.sql`. Verify database migration execution in the target environment.
- Artifact files and generated config live under `{app.storage.path}/{deployName}/` (default `/data/flowops/services`); logs use `{app.logs.path}`.

## Configuration and Deployment

Profiles: `dev`, `prod`, `local`. `DotenvPostProcessor` loads `.env.{profile}` before YAML binding; system environment variables take precedence over dotenv and YAML. Keep `.env` values and runner tokens out of logs and commits. `application.yml` defaults to port 8080 and enables the Nexa master on `127.0.0.1:9090`; check profile overrides and host binding when using a remote runner. `deploy-prod.sh <jar> [profile] [env-file]` runs the app container (host port 8880 by default).
