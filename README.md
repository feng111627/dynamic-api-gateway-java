# dynamic-api-gateway-java

本地可运行的 API 网关核心能力演示：路由转发（运行期热更新）、按路由+调用方维度限流、
会话粘性与实例故障切换。后端用本地 JDK HttpServer 模拟，无需任何外部依赖。

## 模块结构

```
route/    路由表、原子热更新、批量校验（RouteRegistry / RouteTable / RouteUpdateException）
limit/    固定窗口限流（RateLimiter / RateLimitRule / RateLimitDecision）
sticky/   粘性会话与健康注册（StickySessionStore / HealthRegistry / BackendInstance）
gateway/  编排与转发（GatewayService / Forwarder / GatewayResponse）
web/      HTTP 入口与热更新管理端（GatewayController / AdminController / GatewayConfig）
demo/     本地模拟后端（FakeBackend，echo 服务，响应里带实例 id）
```

## 一、路由热更新的生效语义

- **整批原子生效**：`PUT /admin/routes` 提交的是一整批路由规则。网关在更新前对整批做
  完整校验，全部合法才构建新的不可变 `RouteTable` 并通过单次 `AtomicReference` 替换生效。
  对并发读者而言，任何时刻要么看到完整旧表、要么看到完整新表，不存在"某条路由消失一半"
  的中间态。
- **非法整批拒绝**：任何一条规则非法，整批不生效，旧表原样保留。响应 400 并携带分类后的
  失败明细，调用方可区分问题类型：
  - `EMPTY_BATCH`：批次为空
  - `DUPLICATE_ROUTE_ID`：路由 id 重复
  - `INVALID_PATH_PREFIX`：路径前缀非法（必须以 `/` 开头、长度 ≥2、不含 `..`）
  - `EMPTY_BACKENDS`：路由没有可用后端或后端描述不完整
  - `DUPLICATE_BACKEND_ID`：同一路由下后端 id 重复
- **匹配规则**：最长路径前缀优先。

## 二、限流的维度与窗口规则

- **维度**：(路由, 调用方) 两维。规则的 `routeId` / `callerId` 取值语义：
  - `"*"`：通配，**每个具体值独立计数**（如 `callerId="*"` 表示每个调用方各自限额）；
  - `"@all"`：共享，**所有值共用一个计数桶**（如 `callerId="@all"` 表示全体调用方共享限额）；
  - 其他：精确匹配该值。
- **窗口**：固定窗口，以 `windowMillis` 对齐的自然时间片。计数槽位以窗口序号为键的一部分，
  新窗口启用全新槽位：切换瞬间不会重复计数（旧窗口请求不带入新窗口），也不会漏计
  （每个请求只落入一个确定的槽位）。
- **并发安全**：计数在 `ConcurrentHashMap.compute` 内原子完成，并发打量时配额不超发
  （测试：16 线程 × 50 请求对 100 配额，放行数恰好 100）。
- **拒绝结果**：HTTP 429，`errorKind=RATE_LIMITED`，`error` 中指明命中规则 id、路由与调用方，
  可与 404（ROUTE_NOT_FOUND）、503（NO_HEALTHY_BACKEND）等明确区分。

## 三、粘性会话的失效与回切条件

- **绑定**：调用方首次成功转发后，按 (路由, 调用方) 建立到该实例的绑定，TTL 默认 300s
  （`gateway.session.ttl-millis`）。
- **粘住**：会话有效期内，同 (路由, 调用方) 的请求优先落到绑定实例。
- **失效条件**：
  1. 超过 TTL 未重新绑定；
  2. 绑定实例转发失败 —— 实例被标记不健康，绑定被解除（仅当绑定仍指向该实例时才解除，
     避免误删并发下已切换的新绑定）。
- **切换**：实例不健康时自动在路由剩余健康实例中选择下一个重试。每次转发尝试只发往
  一个实例，一个请求绝不会同时发往多个实例；所有实例都失败时返回 503
  `NO_HEALTHY_BACKEND`（错误里列出尝试过的实例），不会无声丢弃。
- **回切**：实例恢复（重新标记健康）后，已粘到其他实例的会话在 TTL 到期重建绑定时
  可以再次落到恢复的实例上。

## 四、本地复现与验证

### 1. 跑单测（覆盖三大业务域）

```bash
mvn test
```

测试日志中的 `BUSINESS-COVERAGE` 行会逐条打印 `[业务域:xxx] 用例:yyy => PASS/FAIL`，
并给出每个业务域的用例数与通过数汇总：

- `RouteHotUpdateTest`（业务域：路由热更新）：合法批次整体生效、非法批次整体拒绝且旧表保留、
  并发更新与读取不出现中间态、空批次被拒绝。
- `RateLimiterConcurrencyTest`（业务域：请求限流）：并发打量配额不超发、窗口切换不重复计数
  不漏计、拒绝结果携带规则标识可区分、不同调用方配额相互独立。
- `StickyFailoverTest`（业务域：会话粘性）：同一会话内请求落到同一实例、实例故障自动切换且
  请求有明确结果、全部实例故障时返回明确错误而非静默丢弃、实例恢复且会话过期后可回切。

### 2. 本地端到端复现

```bash
mvn compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"

# 起两个模拟后端 + 网关（默认端口 8080，后端 18081/18082）
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend backend-1 18081 &
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend backend-2 18082 &
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.Application &
```

验证粘性（同一 caller 始终落同一实例）：

```bash
for i in 1 2 3; do curl -s -X POST localhost:8080/api/hello -H 'X-Caller-Id: alice' -d hi; echo; done
```

验证热更新（整批生效 / 非法整批拒绝）：

```bash
# 合法批次：新增 /api/v2/ 指向 backend-2
curl -s -X PUT localhost:8080/admin/routes -H 'Content-Type: application/json' -d '{"routes":[
 {"id":"r1","pathPrefix":"/api/","backends":[
   {"id":"backend-1","host":"127.0.0.1","port":18081},
   {"id":"backend-2","host":"127.0.0.1","port":18082}]},
 {"id":"r2","pathPrefix":"/api/v2/","backends":[
   {"id":"backend-2","host":"127.0.0.1","port":18082}]}]}'

# 非法批次：重复 id + 非法前缀 -> 400，failures 里分类列出问题，旧表不变
curl -s -X PUT localhost:8080/admin/routes -H 'Content-Type: application/json' -d '{"routes":[
 {"id":"r1","pathPrefix":"/api/","backends":[{"id":"b1","host":"127.0.0.1","port":18081}]},
 {"id":"r1","pathPrefix":"bad","backends":[{"id":"b2","host":"127.0.0.1","port":18082}]}]}'
```

验证限流（默认规则 60 次/分钟/调用方/路由，第 61 次起 429）：

```bash
for i in $(seq 1 65); do curl -s -o /dev/null -w '%{http_code} ' -X POST \
  localhost:8080/api/hello -H 'X-Caller-Id: dave' -d x; done
```

验证故障切换（杀掉 backend-1 后，请求自动切到 backend-2 且结果明确）：

```bash
kill <backend-1 的 PID>
curl -s -X POST localhost:8080/api/hello -H 'X-Caller-Id: carol' -d hi
```
