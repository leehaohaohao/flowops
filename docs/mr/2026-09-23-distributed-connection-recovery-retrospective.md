# 主子节点连接恢复与会话一致性复盘

> 复盘对象：`docs/2026-09-23-runner-connection-recovery-plan.md` 中的连接恢复、身份、断开事件及工作归属问题。
> 涉及仓库：`nexa-protocol`（Java Master / Go Client）、`flowops` 后端、`flowops-executor`；前端属于后续联调验收。
> 实现基线：协议 v0.6.0（连接/认证）、v0.6.1（发送连接鉴权）、v0.6.2（Java 断开事件所有权）；后端依赖 v0.6.2。
> 复盘更新：2026-09-24。本文区分代码与单元测试已完成的部分，以及尚无真实四仓联调证据的部分。

## 1. 结论速览

| 计划指出的问题 | 所在端 | 状态 | 落点 |
|---|---|---|---|
| `Register` 读取无超时 | Go Client | ✅ 已修 | `RegisterContext`（v0.6.0） |
| 缺少连接清理 / 会话重建接口 | Go Client | ✅ 已修 | `Close` / `Reset` / `WithErrorHandler`（v0.6.0） |
| 帧写入与并发写边界需检查 | Go Client + codec | ✅ 已修 | `writeFull` + 写锁（v0.6.0） |
| 错误分类不能仅靠字符串匹配 | Go Client | ⚠️ 部分达成 | 拒绝时返回非 nil 响应体；仍建议引入 typed error |
| `RegisterHandler` 先接管后认证 | Java Master | ✅ 已修 | 先认证 → 再接管 → 后绑定身份（v0.6.0） |
| 旧会话断开误删新会话 / 误报离线 | Java Master | ✅ 已修 | `RemoveIfPresent` 条件移除（v0.6.0/v0.6.1） |
| （计划未显式列出，修复中被发现）报文自称身份可越权 | Java/Go Master | ✅ 已修 | `SessionResolver` / `resolveSession`（v0.6.1） |
| 超时、连接退出交错时重复或漏发断开通知 | Java/Go Master | ✅ 已修 | Go v0.6.1 条件移除；Java v0.6.2 由成功移除者通知 |
| 旧会话回调按 runnerId 清理新会话工作 | FlowOps 后端 | ✅ 代码已修 | 协议会话表判归属；任务/查询按会话代次清理 |
| 同 ID 并发注册使“代次大小”与生效顺序不一致 | FlowOps 后端 | ✅ 代码已修 | 代次只作身份标签；以会话表判断当前归属 |
| 读取会话表后发生接管，旧回调清理新工作 | FlowOps 后端 | ✅ 代码已修 | 工作按目标会话归因；按节点锁协调登记与清理；离线快照写后复查 |
| 任务/查询发送与读取代次之间发生接管 | FlowOps 后端 | ✅ 代码已修 | 一次选取会话对象，发送与归属使用同一目标 |
| 主节点晚启动、重启后的真实部署闭环 | 四仓联调 | ⏳ 待验证 | 真实 Go 节点、MySQL、Docker、状态/日志与前端验收 |

**总评**：协议层的可取消连接、先认证后接管和会话身份校验，是安全重连的前提；后端还必须把断开事件与待处理工作绑定到同一次会话。单元测试已覆盖主要并发交错，但没有真实部署闭环证据，不能把“代码已修”写成“分布式部署已验收”。

---

## 2. 问题所在

### 2.1 客户端（Go Client）

**P1 · `Register` 读取无超时 → 单次尝试可永久挂起**

```go
// v0.5.0 及以前
respEnv, err := c.ReadEnvelope()   // 阻塞读，无 deadline、无 ctx
```

主节点接受 TCP 但不回注册响应（进程假死、认证逻辑死锁、中间设备半开连接）时，调用方永久阻塞。对"连接管理循环"是致命的：退避、退出信号全都无法生效 —— 计划验收场景 2 正是针对这一点。

**P2 · 拨号不可取消**

`Connect` 内部固定 `net.DialTimeout(..., 5*time.Second)`，不接受 ctx。等待重试阶段虽然能响应退出信号，但**一旦进入拨号就必须等满超时**，与"退出信号在三个阶段都能快速结束"冲突。

**P3 · 没有干净的会话重建入口**

`stopCh` 由 `sync.Once` 关闭：

```go
func (c *Client) Disconnect(reason string) error {
    c.once.Do(func() { close(c.stopCh) })   // 只能关一次
    ...
}
```

重连复用同一实例时，`StartHeartbeat` 里的 `case <-c.stopCh` 会立刻命中并退出 —— 新会话心跳静默失效。同时缺少"关闭失败连接但不算正常断开"的入口（`Disconnect` 会发 `DISCONNECT_REQ`，用于认证失败的连接语义不对），也没有幂等 `Close`。

**P4 · 注册被拒被当作成功**

`Register` 只校验响应类型，不检查 `success`：

```go
if respEnv.GetType() != messages.MessageType_REGISTER_RESP { return nil, ... }
return resp, nil   // success=false 也返回 nil error
```

token 错误 / 节点未登记时，子节点会继续跑心跳与读取循环，表现为"进程活着但永远不在线"。且拒绝原因只有错误字符串，上层若想区分"认证失败"与"网络抖动"只能做字符串匹配 —— 计划设计决策 3 明确禁止这种做法。

**P5 · 帧写入的两个边界**

- `WriteFrame` 直接 `w.Write(buf)` 且忽略返回值，底层短写会**截断帧**（对端读到长度头与实际内容不匹配，整个连接的消息流从此错位）
- 多协程并发写同一连接（心跳 / 注册 / 断开）没有串行化，帧可能交织

这两点在"一次性长连接"时代不突出；进入"反复重连、心跳与断开交错"的场景后被放大。

### 2.2 服务端（Java Master）

**P6 · 先接管、后认证（最严重）**

```java
// v0.5.0 实际顺序
RunnerSession session = new RunnerSession(runnerId, ctx.channel(), ...);
RunnerSession oldSession = sessionManager.register(session);      // ① 先接管（替换注册表）
if (oldSession != null) oldSession.close();                       // ② 关掉旧的合法会话
ctx.channel().attr(RUNNER_ID_ATTR).set(runnerId);                 // ③ 认证前就绑定身份
resp = listener.onRegister(session, req);                         // ④ 最后才认证
// 认证失败：仅回一个 success=false 响应，注册表不回滚、身份不解绑、连接不关闭
```

后果有四层：

1. **认证可被绕过为拒绝服务**：任何人用已登记的 `runnerId` + 错误 token 发起注册，就能踢掉正在正常工作的合法节点（旧连接被关、新连接认证失败）
2. **未认证连接已被绑定身份**：第 ③ 步在认证之前，导致认证失败的连接仍会被后续 handler 当作"已注册"处理（v0.6.1 的 `resolveSession` 正是为了彻底消除这种"未认证却可发消息"的状态），其断开事件还会触发一次"节点离线"通知
3. **认证失败留下脏状态**：注册表里留着一个认证未通过的会话，且无人回滚
4. **认证回调异常时更糟**：旧会话已销毁、新会话未确认，节点直接消失

计划把它列为步骤 1，并要求"在允许自动重连前修正" —— 判断正确。**因为一旦子节点开启自动重连，重连风暴会让这个缺陷从"偶发"变成"高频"**：每次主节点抖动后集中重连，任何一个 token 配置错误的节点都在攻击同 ID 的合法节点。

**P7 · 会话身份取自报文自称**

原实现按报文里的 `runnerId` 查/删会话（心跳刷新、`Disconnect` 无条件 `Remove`）。报文是客户端提供的数据，因此：

- 任意已连接者可用他人 `runnerId` 发心跳，为他人"续命"（污染负载与在线状态）
- 任意已连接者可用他人 `runnerId` 发 `DISCONNECT_REQ`，直接摘掉他人节点
- 被同 ID 新连接接管的旧连接，仍能继续发消息影响新会话

**P8 · 超时与断开事件重复、漏发 / 旧连接误报新节点离线**

旧版 `HeartbeatMonitor` 扫描到超时后无条件通知断开，连接退出路径也可能通知；两者交错时业务侧会重复处理。v0.6.1 调整为条件移除后，又出现另一种交错：监控器先 `markTimedOut`，连接退出抢先移除且因“已超时”跳过回调，监控器随后移除失败，**双方都不通知**。更隐蔽的是“扫描期间被接管”：若旧事件只携带 `runnerId`，业务侧会把刚上线的新会话标记离线并失败化其任务。

### 2.3 为什么这些必须先在协议层修

执行器侧能做的补偿是有限的：它可以用 `SetReadDeadline` 造超时、用 `Conn().Close()` 关连接，但**无法修正服务端的接管顺序**，也无法阻止越权消息。分工上：协议层负责"身份与顺序"，执行器负责"重连节奏与退出语义"。P6/P7/P8 不修，执行器做得再对，系统整体也不安全。

### 2.4 后端会话归属问题：协议修好仍不等于业务状态正确

**P9 · 带会话身份的回调在后端退化成 runnerId。** 协议已做到“只为被移除的当前会话发一次事件”，但移除和回调之间，新连接仍可能注册。旧实现的 `FlowOpsMasterListener.onDisconnect(RunnerSession, ...)` 只取 `runnerId`，随后按节点失败化全部 pending 任务/查询、清负载并写离线。这样旧会话的迟到事件仍可能误伤新会话。

**P10 · 代次分配先于协议注册，数值大小不能代表生效顺序。** 后端 `onRegister` 回调先分配标签并返回，协议随后才调用 `SessionManager.register`。若同 ID 的 A、B 并发，A 先取得小标签、B 后取得大标签，但 A 最后写入注册表，真正在线的却是 A。把“最大代次”当当前会话，会在 A 断开时漏清理。

**P11 · 检查会话表与清理之间仍可发生接管。** 后端自己的按节点锁只覆盖后端回调，协议写注册表不持有该锁。旧断开回调读到会话表为空后，新连接仍可注册并开始工作；继续按节点清理会误失败化新工作，离线快照也可能盖过新注册的在线写入。

**P12 · 工作代次若与发送动作分开读取，会归给错误连接。** 原任务路径先 `sendTo(nodeId, ...)`，落库后才读取当前代次；原查询路径先读取代次，再调用 `sendTo`。协议的 `sendTo` 自行查一次会话表。接管发生在两次查表之间时，报文实际接收者与工作记录的代次不同，按代次清理也会失去意义。

这些问题说明业务层需区分三件事：**报文是谁发的、请求实际发给谁、掉线事件属于谁**。三者不能只靠相同的 `runnerId` 推断为同一次会话。

### 2.5 执行器恢复流程：进程存活不等于连接可用

立项时 `main` 对初次连接/注册失败直接退出；注册成功后，读循环或心跳循环失败又可能只结束该协程，主进程继续等待退出信号。前者无法等待晚启动的主节点，后者会形成“进程还在但不再接收任务”的假在线状态。若每次重连复用同一连接对象，还要处理旧心跳、旧读取者和传输中的临时状态，否则会出现两个协程读同一连接或旧会话向新连接写数据。

---

## 3. 解决思路

### 3.1 v0.6.0：把"重连能力"下移到协议层（对应 P1–P5）

| 问题 | 修复 | 关键设计点 |
|---|---|---|
| P1 注册无超时 | `RegisterContext(ctx)`；`Register()` 保留为 10s 默认封装 | 读等待用 ctx 打断：`readEnvelopeContext` 在 ctx 结束时 `SetReadDeadline(time.Now())` 立即打断阻塞读，读完**清除 deadline**，不影响后续读循环 |
| P2 拨号不可取消 | `ConnectContext(ctx, addr)`；`Connect()` 保留 5s 默认封装 | 用 `net.Dialer.DialContext`；连接成功前不替换当前连接 |
| P3 无会话重建 | `Close()` 幂等关闭并将内部 conn 置 nil；`Reset()` = Close + 重建 `stopCh`/`stopDone`；`WithErrorHandler` 回调后台错误 | 让"重连循环复用同一实例"成为受支持的用法；心跳写失败经回调交回上层，而不是静默退出协程 |
| P4 拒绝当成功 | `success=false` 时返回 `(resp, error)` | **故意同时返回非 nil 响应体**，使上层能做类型化分类（如判定 `resp != nil && !resp.GetSuccess()`）而不必匹配字符串；这是 P4 与决策 3 的折中 |
| P5 帧边界 | `codec.WriteFrame` 引入 `writeFull` 循环写；client 增加 `writeMu` 串行化帧写入 | 整帧要么写完要么报错；心跳/注册/断开不再交织 |

### 3.2 v0.6.0–v0.6.2：把"身份与事件"收敛到唯一规则（对应 P6–P8）

**① 注册三步：先认证 → 再接管 → 后绑定**（`RegisterHandler`）

```java
RunnerSession candidate = new RunnerSession(...);   // 候选会话，不触碰注册表
resp = listener.onRegister(candidate, req);         // 1. 先认证
if (!resp.getSuccess()) { rejectAndClose(...); }    //    拒绝：先 flush 失败响应再关闭，不绑定身份
RunnerSession old = sessionManager.register(candidate); // 2. 认证通过才原子替换
if (old != null) old.close();
SessionResolver.bind(ctx, runnerId);                // 3. 绑定身份（此后断开事件才代表该 runner 离线）
```

拒绝路径的三个"不"：**不注册、不绑定身份、不影响旧会话**。不绑定身份尤其关键 —— 否则未认证连接的断开事件会被当成一次节点离线。

**② 会话身份只认连接绑定**（`SessionResolver` / `resolveSession`，两端同构）

```java
// 解析规则：连接已绑定身份 && 注册表中该 runnerId 的会话 channel == 本连接 channel
sessionManager.get(runnerId).filter(s -> s.getChannel() == ctx.channel());
```

由此自然得到：未注册连接 → 解析失败；被接管的旧连接 → 解析失败。而报文里的 `runnerId` / `Envelope.source_id` 降级为**一致性检查**（`matchesRunnerId` / `matches`，声明为空则跳过），永不用于选择会话。所有已注册消息（心跳、断开、任务回执、状态/日志回执、产物请求）统一走这个入口。

**③ 事件所有权：谁条件移除成功，谁通知恰好一次**

```go
// HeartbeatMonitor.doCheck()
removedCurrent := m.sessions.RemoveIfPresent(session.RunnerId, session)
session.Close()
if removedCurrent { notifyDisconnect(m.listener, session, "heartbeat_timeout") }
```

- `RemoveIfPresent(runnerId, expected)` 只在映射值仍是 `expected` 时移除并返回 true
- 超时、主动断开、连接退出三条路径共用该规则 → 天然去重
- 被接管的过期旧连接：移除失败 → 只清理连接、不通知（新节点保持在线）
- Go Master 新增可选接口 `SessionDisconnectListener.OnDisconnectSession(session, reason)`；Java Master 的 `NexaMasterListener` 增加 `onDisconnect(RunnerSession, reason)` 默认重载。两端都能向业务侧交付会话身份，并兼容旧签名。

**④ Java v0.6.2：把“超时标记”与“通知所有权”分开**

Java `HeartbeatMonitor` 的 `markTimedOut` 只标识状态及上报原因，不代表事件已通知。监控器、`channelInactive`、主动断开中，**谁成功 `removeIfPresent`，谁调用带会话身份的回调**；连接退出路径即使看到 `isTimedOut=true`，也不能在自己成功移除后跳过通知。确定性交错测试固定“标记超时 → 连接退出移除 → 监控器移除失败”，验证恰好一次 `heartbeat_timeout`。v0.6.1 的“先条件移除”只是中间状态，不能当作最终闭环。

### 3.3 FlowOps 后端：让断开清理对准那次会话（对应 P9–P11）

1. `SessionTracker` 给每次认证通过的连接绑定唯一代次标签；**标签仅表示身份，不用大小判断先后**。断开回调先在协议会话表核对：若同 ID 还有生效会话，说明事件会话已经被接管，跳过对在线节点的清理。
2. `PendingTask` 和 `QueryManager.PendingQuery` 保存其目标会话代次。旧事件仅失败化与事件代次相同的工作；无身份的旧回调采用保守语义，不失败化无法归属的工作，等待自身超时机制收敛。
3. 后端注册、断开、工作发送/登记及任务回执使用同一按节点锁，避免后端内部的“检查后被另一项后端操作插入”。协议注册表写入不在这把锁内，所以仍需第 2 条的按会话过滤。
4. 离线快照写入后复查协议会话表，若新会话已注册，立即修正为在线。数据库快照与协议注册表无法做单次原子事务；在线展示以实时会话为准。彻底消除最后的快照窗口需要数据库侧带会话代次的条件更新，当前未实施。

### 3.4 发送目标与归属合一（对应 P12）

`NodeService.getCurrentTarget()` **只读一次协议会话表**，返回同一个 `RunnerSession` 及其绑定代次。任务和查询通过这个选中的会话对象发送，并用它的代次登记 pending；不再让 `sendTo` 另查一次会话、前后另取代次。发送和登记在按节点锁内完成；任务回执也经同一锁，避免回执先于 pending 登记到达业务层。此设计保证“记录的归属是所选发送连接”，但不声称异步网络写入已被远端确认；接管时若所选旧连接已经关闭，发送会失败并按既有失败/超时语义处理。

### 3.5 执行器：一个循环管理完整会话

`Runner.Run(ctx)` 成为唯一连接管理者；每次尝试新建 client，拨号与注册分别设可取消的超时。注册成功才启动一套心跳和读取协程；任一协程报错便取消本次会话、关闭连接、等待协程退出，再按有界退避重试。认证/登记被明确拒绝时转入低频等待，避免高频刷请求；收到退出信号则打断等待/拨号/注册/已连接阶段。该实现解决“主节点晚启动”和“运行中断线后的自动重连”的代码路径，但真实主子节点、数据库与 Docker 的完整闭环仍需计划步骤 5 验收。任务不自动重放，避免断线后重复执行部署动作。

---

## 4. 测试思路

### 4.1 分层策略

| 层次 | 手段 | 覆盖目标 |
|---|---|---|
| 协议客户端 | 单元测试 + 假 master（`silentMaster`：接受连接但从不回响应） | 超时/取消/重建/拒绝等契约与边界 |
| 协议服务端 | `EmbeddedChannel`（Java）/ 构造 `ConnContext`（Go）+ 录制型 listener | 身份鉴权、接管、事件计数等**不变量** |
| 执行器 | 真 socket + 假 master 的行为验证 | 重连节奏、退避、退出语义（属于执行器职责，不在协议测试范围） |

分层理由：协议层的不变量是"身份与顺序"（谁的消息有效、事件通知几次），适合用小而全的确定性用例穷举；而"1s→30s 退避""退出信号 0s 结束"这类**时序行为**属于执行器，用真实 socket 测更可信。

### 4.2 Go Client 单元测试（7 例，`go/client/client_test.go`）

| 用例 | 验证点 |
|---|---|
| `TestRegisterContextTimesOutWhenMasterSilent` | 主节点静默时返回 `context.DeadlineExceeded`，且及时返回（不永久阻塞） |
| `TestRegisterContextCancelable` | ctx 取消立即打断等待，错误为 `context.Canceled` |
| `TestResetAllowsCleanReconnect` | `Reset` 后 conn 被清空，可连第二个地址建立干净会话 |
| `TestCloseIsIdempotent` | `Close` 重复调用不报错、不 panic |
| `TestSendWithoutConnection` | 未连接时发送返回明确错误而非 panic |
| `TestRegisterRejectedReturnsError` | 拒绝时返回 error **且**返回非 nil 的失败响应体；同时校验被拒的 token 确实发出去了 |
| `TestRegisterCarriesToken` | token 与 runnerId 随注册请求发出（L1 契约） |

### 4.3 Master 身份鉴权（Go 11 例 / Java 13 例）

两端用例同构，围绕三条不变式：

| 不变式 | 代表用例 |
|---|---|
| 未注册连接不能产生任何副作用 | `TestUnregisteredConnectionCannotRefreshHeartbeat`、`...CannotDisconnectRunner` |
| 冒用他人身份被拒（心跳/断开/回执/产物） | `TestRegisteredConnectionCannotHeartbeatAsAnotherRunner`、`...CannotDisconnectAnotherRunner`、`...CannotForgeQueryResults`、`...CannotForgeArtifactIdentity`（Java 另有 `...CannotForgeTaskResult`） |
| 接管后旧连接彻底失效、新会话照常工作 | `TestSupersededConnectionMessagesAreRejected`、`TestSupersedingSessionKeepsWorking`、`TestNormalArtifactRequestIsAccepted` |

Java 侧另加 `oldConnectionCloseDoesNotReportNewSessionOffline` —— 直接断言 P6/P8 的业务后果（旧连接断开不得把新会话报离线）。

### 4.4 HeartbeatMonitor 通知不变量（Go 10 例 / Java 8 例）

核心是"**恰好一次**"，用三类交错构造：

| 场景 | 用例 |
|---|---|
| 超时本身 | 超时移除并通知一次；重复扫描不重复通知 |
| 超时 vs 连接退出 | 先超时后断开、交错并发 → 只通知一次 |
| 超时 vs 接管 | 被接管的过期旧会话只清理不通知；**扫描期间被接管**（用 `SessionSource` 注入交错）不通知 |
| 兼容性 | 旧 `Listener` 仍能收到断开事件 |

Go 侧额外做 `TestConcurrentTakeoverDuringTimeoutScan`：并发接管与超时扫描交错 **200 轮**校验不变量 —— 这类"条件移除 + 通知"的竞态靠单次用例很难暴露，靠高频交错压出来。抽 `SessionSource` 接口正是为了让"扫描中被接管"这一瞬时交错可被确定性地构造出来。

### 4.5 执行器侧行为验证（本次实测，协议 v0.6.1）

用真 socket + 假 master 验证，覆盖计划验收场景中属于执行器的部分：

| 场景 | 实测结果 |
|---|---|
| 主节点未监听 | 保持运行不退；退出信号 **0s** 结束 |
| 拨号不可达地址 + ctx 取消 | **500ms**（由 ctx 决定）中断，而非等满 `connect_timeout=10s` |
| 主节点只接 TCP 不回响应 | 按注册超时重试 3 次，每次释放连接 |
| 已注册后主节点断开 | 自动清理旧会话并重新注册（间隔 ≈ 退避 + 模拟存活时间） |
| token 无效 | 重试间隔 2.08s（低频受控），非每秒刷请求 |
| 认证错误分类 | `errors.As` 命中执行器定义的 `registerRejectedError` |
| 注册后 / 退出时 | 心跳 `source_id` = 自身 runnerId；退出发送 `DISCONNECT`（配合 v0.6.1 条件移除会话） |

> 该验证为临时程序，跑完即删（仓库不留测试文件）。

### 4.6 后端与 Java 协议的确定性交错回归

| 问题 | 固定的顺序与验证 |
|---|---|
| Java 漏通知（P8） | `HeartbeatMonitorTest#timeoutAndChannelInactiveInterleavingNotifiesExactlyOnce` 固定“标记超时 → 连接退出先移除 → 监控器移除失败”，断言携带原会话且恰好一次通知 |
| 代次顺序反转（P10） | `FlowOpsMasterListenerReconnectTest#liveSessionWithOlderGeneration_isStillCleanedUp` 固定“小标签 A 最后成为生效会话”，断言 A 掉线仍完成清理 |
| 旧回调晚于接管（P9） | `#supersededSession_isSkippedWhileSuccessorIsLive` 断言新会话工作与在线状态不被旧回调触碰 |
| 查表后接管（P11） | `#takeoverBetweenRegistryCheckAndCleanup_scopesFailureAndRepairsSnapshot` 在查表后注入接管，断言清理按旧代次归因，离线快照写后修正 |
| 工作过滤（P11） | `RemoteTaskManagerTest`、`QueryManagerTest` 验证不同代次、未知代次与重复清理的边界 |
| 发送与归属（P12） | `NodeServiceSessionTargetTest` 固定选目标后接管；`RemoteDeployDispatcherSessionTest` 验证任务登记代次来自实际选中的发送连接；`QueryManagerTest#oldDisconnectDuringSendCannotFailSuccessorQuery` 验证发送期间旧事件不结束新查询 |

2026-09-24 本地回归：后端定向 **25 项**、协议 Java **26 项**通过，包含模拟 Java 客户端的产物传输链路测试。它们证明所列代码路径与可控交错，不证明真实 Go 节点、MySQL 和 Docker 的端到端行为。临时实验或旧文中的用例数量应以当次测试报告为准。

### 4.7 仍未覆盖 / 待联调补齐

| 缺口 | 说明 | 归属 |
|---|---|---|
| 真实 Java Master ↔ 真实 Go 执行器端到端 | 计划步骤 5：主节点晚启动、运行中重启、接管窗口下的在线状态与任务状态一致性 | 四仓联调 |
| 半开连接（TCP keepalive / 中间设备静默丢弃） | 现有超时覆盖"不回响应"，未覆盖"连接看似存活但双向黑洞" | 协议层后续 |
| 大产物传输与重连并发 | 产物分块接收期间断线、重连后传输状态清理（当前靠内存态丢弃，未做压测） | 执行器 + 联调 |
| 接管瞬间的负载/在线状态回归 | 单元测试已覆盖会话归属与按代次清理；仍缺真实节点和数据库上的端到端断言 | 联调 |
| 数据库在线快照的最终原子性 | 当前写离线后复查接管者并修正；最后一次复查后仍可能发生接管，彻底消除需带会话代次的条件写入 | 后端后续加固 |
| 执行器侧恢复验证未入库 | 临时验证覆盖了 6 个场景，但没有固化为可重复执行的用例 | 执行器 |

---

## 5. 尚待处理的风险

| # | 发现 | 影响 | 建议 |
|---|---|---|---|
| N1 | 执行器自行实现心跳与回执，直接调用 `codec.WriteFrame(conn, ...)`，**绕过了 client 的 `writeMu`**；而 v0.6.0 的 `writeFull` 在短写时会**循环多次 Write** | 理论上帧写入不再有单次 Write 的原子性保证，并发写者（心跳 vs 回执）存在交织窗口。当前 `net.TCPConn` 通常一次写完，风险低但依赖实现细节 | 在会话对象上加写锁，或由协议库暴露通用 `Send(envelope)` 供业务层复用其写锁 |
| N2 | 认证错误分类仍依赖"拒绝时返回非 nil 响应体"这一约定 | 约定不被编译器强制，未来若有人改成 `return nil, err`，分类会静默退化为"网络错误"→ 认证失败变成高频重试 | 协议库引入 typed error（如 `ErrRegisterRejected`），执行器改用 `errors.Is/As` |
| N3 | 帧上限固定 10MB，产物块 1MB，二者无动态校验 | 若未来把块调大（如 8MB）会逼近上限；无编译期/启动期校验 | 协议库导出 `MaxFrameSize`，产物传输侧启动时校验块大小 |
| N4 | 执行器侧恢复验证未入库 | 每次改动需重写验证程序，回归成本高 | 固化为脚本或 `-tags e2e` 的可选测试 |
| N5 | 产物接收期间直接读取同一连接，其他消息可能被消费或忽略 | 并发任务/查询与大产物传输时可能丢消息 | 联调先复现；若成立，收敛为单一读取者与请求分发，不与本次重连修复混做 |

---

## 6. 可复用的经验

1. **认证与状态接管必须"先认证、后生效"**。任何"先登记再校验"的写法，都把认证接口变成了踢人接口 —— 顺序错了，功能测试还全绿（因为正常路径两种顺序结果相同），只有引入失败路径用例才会暴露。
2. **身份取连接绑定，不取报文自称**。凡是"消息里带着我是谁"的设计，都需要一条"该声明是否与连接身份一致"的检查，否则越权与冒用几乎必然出现。
3. **通知类副作用要有"事件所有权"**。多路径（超时、断开、退出）争抢同一个副作用时，用"条件移除成功者负责通知"这类规则，比在每个路径上加去重标记更可靠。
4. **重连能力应下沉到通信库**：可取消的拨号/注册、幂等的关闭、干净的会话重建，都是通信库的职责；业务层只编排"退避节奏与退出语义"，否则每个使用方都要重新发明一遍超时与清理。
5. **超时失败要区分"暂时"与"永久"**：网络类错误重试、配置/认证类错误低频重试或停止。分类必须靠类型而非字符串，否则文案一改就失效。
6. **并发不变量靠高频交错验证**：条件移除 + 通知这类竞态，单次用例通过不代表正确；200 轮并发交错 + 可注入交错的接口设计（`SessionSource`）是性价比最高的做法。
7. **代次是身份标签，不是“当前”的同义词**：认证回调与协议注册表写入分属两个阶段，标签分配顺序可能与会话实际生效顺序相反。归属判断看协议当前绑定，失败化看事件与工作的同一标签。
8. **发送目标与工作归属必须来自同一次选择**：在发送前后各查一次注册表会得到不同连接。选定会话对象、用它发送并登记它的标签，再用按节点锁协调业务登记与断开清理。
9. **分层完成状态要分开写**：单元测试闭环、真实节点恢复、部署/状态/日志联调是三种不同证据。计划步骤 5 未通过前，不应把“修复已实现”写成“分布式部署完成”。

---

## 7. 附：版本与关键改动对照

| 版本 | 提交 | 关键文件 |
|---|---|---|
| v0.6.0 | `b3ad5dd` fix: 注册改为先认证后接管，客户端支持可取消重连会话 | `java/.../RegisterHandler.java`、`go/client/client.go`、`go/client/client_test.go`、`go/master/session_manager.go`、`go/codec/frame.go` |
| v0.6.0 | `3d8853e` fix: 恢复注册被拒时的显式错误返回 | `go/client/client.go` |
| v0.6.1 | `670bc94` fix: 已注册消息按发送连接会话鉴权，超时仅通知被移除的会话 | `java/.../SessionResolver.java`、`go/master/session_resolver.go`、`handler_heartbeat.go`、`handler_disconnect.go`、`heartbeat.go`、`listener.go`、`session_manager.go`、各 handler、`session_identity_test.go`、`heartbeat_test.go` |
| v0.6.1 | `09d52eb` docs: 更新 CHANGELOG，发布 Go v0.6.1 和 Java v0.6.1 | `CHANGELOG.md` |
| Java v0.6.2 | `1661de2` fix: 统一断开事件所有权 | `java/.../HeartbeatMonitor.java`、`MasterChannelHandler.java`、`HeartbeatMonitorTest.java` |
| 后端适配 v0.6.2 | 当前工作树（尚未提交） | `FlowOpsMasterListener`、`SessionTracker`、`NodeService`、`RemoteDeployDispatcher`、`RemoteTaskManager`、`QueryManager` 及相关测试 |

> 关联：执行器侧连接管理循环、退避和退出语义位于 `flowops-executor` 当前工作树，使用协议 Go Client 的 `ConnectContext` / `RegisterContext` / `Close`。真实四仓联调仍以连接恢复计划的结束条件为准。
