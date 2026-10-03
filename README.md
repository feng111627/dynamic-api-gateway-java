# dynamic-api-gateway-java

本地可运行的 API 网关核心能力演示：路由转发（运行期热更新）、按路由+调用方维度限流、
会话粘性与实例故障切换、请求/响应完整透传、实例自动恢复（暖机缓升）、
加权负载均衡与单实例并发容量保护。后端用本地 JDK HttpServer 模拟，无需任何外部依赖。

## 模块结构

```
route/    路由表、原子热更新、批量校验（RouteRegistry / RouteTable / RouteUpdateException）
limit/    固定窗口限流（RateLimiter / RateLimitRule / RateLimitDecision）
sticky/   粘性会话与健康注册（StickySessionStore / HealthRegistry / BackendInstance）
balance/  负载均衡与并发容量（LoadBalancer / WeightedRoundRobinLoadBalancer / ConcurrencyLimiter）
health/   实例恢复探测（HealthChecker：UNHEALTHY -> RECOVERING -> HEALTHY）
gateway/  编排与透传转发（GatewayService / GatewayRequest / Forwarder / GatewayResponse）
web/      HTTP 入口与管理端（GatewayController / AdminController / GatewayConfig）
demo/     本地模拟后端（FakeBackend，echo 服务，支持定制应答/延迟/并发统计）
```

## 一、路由热更新的生效语义

- **整批原子生效**：`PUT /admin/routes` 提交的是一整批路由规则。网关在更新前对整批做
  完整校验，全部合法才构建新的不可变 `RouteTable` 并通过单次 `AtomicReference` 替换生效。
  对并发读者而言，任何时刻要么看到完整旧表、要么看到完整新表，不存在"某条路由消失一半"
  的中间态。
- **非法整批拒绝**：任何一条规则非法，整批不生效，旧表原样保留。响应 400 并携带分类后的
  失败明细（`EMPTY_BATCH` / `DUPLICATE_ROUTE_ID` / `INVALID_PATH_PREFIX` /
  `EMPTY_BACKENDS` / `DUPLICATE_BACKEND_ID`）。
- **匹配规则**：最长路径前缀优先。
- 路由的后端实例现在可携带 `weight`（权重，默认 1）与 `maxConcurrency`
  （并发上限，默认 0 = 不限），见第五、六节。

## 二、限流的维度与窗口规则

- **维度**：(路由, 调用方) 两维。规则的 `routeId` / `callerId` 取值语义：
  `"*"` 每个具体值独立计数；`"@all"` 所有值共享一个桶；其他为精确匹配。
- **窗口**：固定窗口，以 `windowMillis` 对齐的自然时间片；新窗口启用全新槽位，
  切换瞬间不重复计数也不漏计。
- **并发安全**：计数在 `ConcurrentHashMap.compute` 内原子完成，配额不超发。
- **拒绝结果**：HTTP 429，`errorKind=RATE_LIMITED`，可与其他错误明确区分。

## 三、粘性会话的失效与回切条件

- **绑定**：调用方首次成功转发后，按 (路由, 调用方) 建立到该实例的绑定，TTL 默认 300s
  （`gateway.session.ttl-millis`）。
- **失效条件**：超过 TTL 未重新绑定；或绑定实例转发失败（实例被标记不健康、绑定被解除，
  仅当绑定仍指向该实例时才解除，避免误删并发下已切换的新绑定）。
- **切换**：实例不健康时自动在剩余健康实例中重选。每次转发尝试只发往一个实例；
  所有实例都失败时返回 503 `NO_HEALTHY_BACKEND`（错误里列出尝试过的实例），不静默丢弃。
- **回切**：实例恢复后，已粘到其他实例的会话在 TTL 到期重建绑定时，
  按负载均衡策略分配，可以自然落到恢复的实例上（见第四、五节）。

## 四、请求与响应的完整透传（本轮新增）

- **入口承接各种请求语义**：网关入口 `@RequestMapping("/**")` 不限制方法，
  GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS 等都能承接，不会把语义不同的请求归一成同一种。
- **请求透传**：调用方的请求方法、查询串、业务请求头、请求体原样发给后端
  （仅跳过 Connection/Host/Content-Length 等逐跳头）。
- **响应透传**：后端返回的状态码（包括 4xx 业务错误，不会被改写成网关故障）、
  响应头（跳过逐跳/长度类头）、响应体原样带回给调用方。
- **错误契约不变**：只有网关自身产生的拒绝才返回分类 JSON 错误
  （`ROUTE_NOT_FOUND` / `RATE_LIMITED` / `NO_HEALTHY_BACKEND` / `BACKEND_AT_CAPACITY`）。

## 五、实例自动恢复与暖机缓升（本轮新增）

- **状态机**：`HEALTHY -> UNHEALTHY -> RECOVERING -> HEALTHY`。
  转发失败标记 `UNHEALTHY`；`HealthChecker` 周期探测（默认 2s，
  `gateway.recovery.probe-interval-millis`）**只探测不健康/暖机中的实例**，
  健康实例不被探测、不会被误判。探测成功进入 `RECOVERING` 暖机期；
  暖机期满（默认 5s，`gateway.recovery.warmup-millis`）且持续探测成功回到 `HEALTHY`；
  暖机期探测失败退回 `UNHEALTHY`。
- **自动回流量**：恢复无需人工改配置或重启服务。`RECOVERING` 起实例即可承接新会话；
  已粘在其他实例上的会话按既有 TTL 失效规则到期后自然重建绑定。
- **暖机缓升**：`RECOVERING` 期间实例的有效权重被压到 1（不论配置权重多大），
  恢复瞬间不会被全量流量打垮；回到 `HEALTHY` 后恢复配置权重。
- **不打断在途请求**：状态迁移只影响后续选实例，正在处理的请求在原实例上正常完成。
- **可观察**：每次状态迁移都会发布 `HealthEvent`（监听器）并打日志；
  `GET /admin/health` 实时返回每个实例的当前状态。

## 六、负载均衡与单实例容量保护（本轮新增）

- **多实例分担**：新会话（无粘性绑定或绑定已失效）按加权轮询分散到健康实例，
  不再固定压在路由里排第一的实例上。权重按实例配置（`weight`，默认 1）。
- **并发上限**：每个实例可配 `maxConcurrency`（默认 0 = 不限）。在途请求数到达上限后：
  - 先尝试路由下其他健康实例；
  - 所有健康实例都满时返回 **503 `BACKEND_AT_CAPACITY`**——与 429 `RATE_LIMITED`、
    404 `ROUTE_NOT_FOUND`、503 `NO_HEALTHY_BACKEND` 明确区分；
  - 绝不超发（后端在途并发不超过上限），也绝不静默丢弃（每个请求都有明确结果）。
- **配额释放**：请求结束（成功或失败）即释放该实例配额，后续请求正常放行。

## 与上一轮的兼容范围

- **保留的 API**：`GatewayService.handle(path, callerId, body)`（等价于无查询串的 POST）、
  `BackendInstance(id, host, port)`（权重 1、不限并发）、`GatewayService` 五参构造
  （默认加权轮询 + 不限并发）、`HealthRegistry` 的 `markHealthy/markUnhealthy/isHealthy`。
- **行为变化**：
  1. 新会话的选实例策略从"路由里第一个健康实例"变为"加权轮询"。等权（默认）时
     新会话在各健康实例间均匀分散；粘性绑定不受影响。会话粘性测试中断言"恢复后
     下一次请求必回切"的用例相应调整为"会话过期后几轮内自然回切"，业务保证不变。
  2. 网关入口成功响应从"包装成 JSON（含 backendId/backendBody）"变为"原样回传后端
     状态码/响应头/响应体"；错误响应仍是分类 JSON，`errorKind` 契约不变。
  3. 错误枚举新增 `BACKEND_AT_CAPACITY`，既有枚举值与语义不变。

## 七、本地复现与验证

### 1. 跑单测（六大业务域）

```bash
mvn test
```

测试日志中的 `BUSINESS-COVERAGE` 行逐条打印 `[业务域:xxx] 用例:yyy => PASS/FAIL`，
并给出每个业务域的用例数与通过数汇总：

- `RouteHotUpdateTest`（路由热更新，4 例）：整批生效、整批拒绝、并发无中间态、空批拒绝。
- `RateLimiterConcurrencyTest`（请求限流，4 例）：配额不超发、窗口切换不重不漏、
  拒绝可区分、调用方相互独立。
- `StickyFailoverTest`（会话粘性，4 例）：同会话同实例、故障自动切换、全挂明确报错、
  恢复后自然回切。
- `PassThroughTest` + `WebEntryPassThroughTest`（请求透传，7 例）：方法/头/查询/体透传、
  读写删改语义不归一、后端状态码/响应头/体原样带回、入口承接各种方法、错误仍可区分。
- `InstanceRecoveryTest`（实例恢复，4 例）：自动回流量且健康实例不误判、暖机缓升、
  状态变化可观察、恢复不打断在途请求。
- `LoadBalanceCapacityTest`（负载均衡与容量，5 例）：等权均匀分散、按权重分散、
  上限拒绝可区分且不超发、多实例上限独立、配额释放后继续承接。

### 2. 本地端到端复现

```bash
mvn compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"

java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend backend-1 18081 &
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend backend-2 18082 &
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.Application &
```

**透传**（方法/头/查询/体原样到后端，状态码/响应头/体原样带回）：

```bash
curl -si -X PUT 'localhost:8080/api/items/42?flag=1' -H 'X-Caller-Id: alice' \
     -H 'X-Custom-Header: v1' -d '{"price":10}'
curl -si -X DELETE 'localhost:8080/api/items/42' -H 'X-Caller-Id: alice'
curl -si 'localhost:8080/api/__respond?status=418&header=X-Tea-Kind:green&body=teapot'
```

**负载均衡**（不同调用方的新会话分散到两个实例）：

```bash
for u in a b c d e f; do
  curl -s localhost:8080/api/hello -H "X-Caller-Id: $u" | grep -o 'backend=[a-z0-9-]*'
done
```

**自动恢复 + 可观察**（杀掉 backend-1 再重启，健康状态自动迁移）：

```bash
watch -n1 'curl -s localhost:8080/admin/health'   # 另开一个终端观察
kill <backend-1 的 PID>
curl -s localhost:8080/api/hello -H 'X-Caller-Id: carol'   # 触发故障检测，自动切走
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend backend-1 18081 &
# 几秒内 /admin/health 里 backend-1 依次变为 RECOVERING -> HEALTHY，无需人工干预
```

**并发容量**（把 backend-1 的并发上限配成 1，慢请求下并发打量）：

```bash
curl -s -X PUT localhost:8080/admin/routes -H 'Content-Type: application/json' -d '{"routes":[
 {"id":"r1","pathPrefix":"/api/","backends":[
   {"id":"backend-1","host":"127.0.0.1","port":18081,"maxConcurrency":1},
   {"id":"backend-2","host":"127.0.0.1","port":18082,"maxConcurrency":1}]}]}'

# 慢接口下并发 6 个请求：2 个成功（每实例 1 个），4 个 503 BACKEND_AT_CAPACITY
for i in $(seq 1 6); do
  curl -s -o /dev/null -w '%{http_code}\n' "localhost:8080/api/slow?delayMs=1000" \
    -H "X-Caller-Id: cap-$i" &
done; wait
```

**限流**（默认 60 次/分钟/调用方/路由，第 61 次起 429）：

```bash
for i in $(seq 1 65); do curl -s -o /dev/null -w '%{http_code} ' \
  localhost:8080/api/hello -H 'X-Caller-Id: dave'; done
```
