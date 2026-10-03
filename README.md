# dynamic-api-gateway-java

本地可运行的 API 网关演示：路由转发（运行期热更新）、按路由+调用方维度限流、
会话粘性与实例故障切换，以及本轮新增的三大能力——**完整请求透传、实例自动恢复（渐进回流量）、
多实例加权负载均衡与单实例并发容量保护**。后端用本地 JDK HttpServer 模拟，无需任何外部依赖。

## 模块结构

```
route/    路由表、原子热更新、批量校验（RouteRegistry / RouteTable / RouteUpdateException）
limit/    固定窗口限流（RateLimiter / RateLimitRule / RateLimitDecision）
sticky/   粘性会话、健康注册与状态翻转事件
          （StickySessionStore / HealthRegistry / BackendInstance[含 weight、maxConcurrency]）
balance/  加权负载均衡与容量保护（LoadBalancer / InstanceConcurrencyLimiter）
health/   健康探测与自动恢复（HealthProbe / HttpHealthProbe / HealthRecoveryService）
gateway/  编排与透传转发（GatewayService / Forwarder / GatewayRequest / ForwardRequest /
          GatewayResponse / ForwardResult）
web/      HTTP 入口与管理端（GatewayController / AdminController / GatewayConfig /
          RawBodyCachingFilter：在表单解析前缓存原始请求体，保证 PUT/DELETE 表单请求体不丢）
demo/     本地模拟后端（FakeBackend：回显方法/头/查询/体、自定义状态码、/hold/ 挂起）
```

---

## 本轮新增能力

### 四、请求完整透传

- **入口承接各种 HTTP 语义**：`GatewayController` 用 `@RequestMapping("/**")` 统一承接
  GET / POST / PUT / DELETE / PATCH / HEAD / OPTIONS 等全部方法，不再只认 POST；
  `/admin/**` 由管理端精确匹配优先处理。
- **请求原样透传**：HTTP 方法、全部请求头、原始查询串（含空值、百分号编码）、请求体，
  由 `GatewayRequest`/`ForwardRequest` 一路带到后端，不做语义归一；
  仅剔除 `Host/Connection/Content-Length/Transfer-Encoding` 等逐跳或由 HTTP 客户端重算的头。
- **响应如实回传**：后端的状态码（201/404/5xx 等都算“后端正常回答”，**不会**因此触发故障切换）、
  响应头、响应体原样写回调用方；网关只在自身出错误时才返回分类 JSON。
- **错误结果可区分**：无路由 404 `ROUTE_NOT_FOUND`、限流 429 `RATE_LIMITED`、
  后端全挂 503 `NO_HEALTHY_BACKEND`、容量打满 503 `BACKEND_OVERLOADED`，
  错误响应同时带 `X-Gateway-Error-Kind` 响应头。

### 五、实例自动恢复 + 渐进回流量

- **自动探活恢复**：`HealthRecoveryService` 周期（默认 1s）探测所有“当前不健康”实例；
  能连通并拿到任意 HTTP 响应（哪怕 4xx/5xx）即视为存活，自动标记健康，无需人工改配置或重启。
  探测**只做救活**，从不据此把健康实例标记为不健康，因此不可能误判健康实例。
- **不打断在途请求**：健康状态只影响后续“新选择”，已在转发的连接不中断、不改道。
- **渐进回流量（预热闸门）**：实例刚恢复时进入预热期（默认 5s），期间只承接
  约 `1/gateway.recovery.share-divisor`（默认 1/4）的新会话，且预热期权重固定为最小权重，
  避免恢复瞬间被一次性砸垮；预热结束自动恢复全权重。
- **粘性规则不变**：恢复后新建立的会话正常可能分到它；原来粘在其他实例上的会话在
  **既有 TTL 到期前不被抢占**，到期重新绑定时才自然回到它。
- **状态外部可观察**：健康翻转通过 `HealthRegistry` 的监听器发出
  `HEALTH-CHANGE backend=.. state=HEALTHY/UNHEALTHY` 日志；`GET /admin/health`
  返回按路由聚合的每实例 `weight/maxConcurrency/state` 与全局不健康集合。
  只有真实翻转才产生事件，重复标记不抖动。

### 六、加权负载均衡 + 单实例并发容量保护

- **加权平滑轮询（smooth WRR，Nginx 风格）**：同一路由多个健康实例间，新会话按
  `weight`（缺省 1）分散，长期分布与权重成正比、短期平滑交替，不会老压同一实例。
  已建立的粘性会话仍优先走绑定实例；健康拓扑翻转时重置轮询状态，保证恢复后选择不偏斜。
- **每实例并发上限**：`maxConcurrency`（0 表示不限）按请求动态读取，热更新调大调小即时生效。
- **不超发、不静默丢弃**：到达上限时请求进入 FIFO 公平队列等待名额，等待上限
  `gateway.capacity.acquire-timeout-millis`（默认 200ms，设为 0 表示不排队）；
  等不到则尝试其他健康实例；**所有**健康实例都拿不到容量时返回
  503 `BACKEND_OVERLOADED`，与限流(429)/无路由(404)/后端全挂(503 `NO_HEALTHY_BACKEND`)
  明确区分。请求结束后才归还名额。

---

## 与原有行为的兼容范围

- `BackendInstance(id, host, port)` 旧构造器保留，等价于 `weight=1, maxConcurrency=0`，
  老用例/老配置无需改动；`PUT /admin/routes` 的 `weight`、`maxConcurrency` 均为可选字段
  （缺省 1 / 0），**旧批次报文完全兼容**。
- 限流、热更新、粘性 TTL、故障切换的语义与错误分类保持不变；`GatewayService` 旧的
  5 参构造器与 `handle(path, caller, body)`（按 POST）便捷方法保留。
- 唯一的**有意行为变化**：新会话选实例由“总选路由下第一个健康实例”改为“按权重平滑轮询”。
  因此在故障场景里，某个已死实例不再保证立刻被“撞上”而标记不健康；只有真正往它转发失败时
  才标记。粘性会话在绑定实例故障时仍会立刻撞上、标记并切换（行为不变）。
- 成功响应体由旧的“JSON 包裹”改为**后端原始响应体透传**（这是本轮要修的语义）；
  网关自身错误仍是 JSON。如需机器读取后端归属，成功响应带后端的 `X-Backend-Id` 头。

---

## 一、路由热更新的生效语义（原有，保持不变）

- **整批原子生效**：`PUT /admin/routes` 提交整批规则，先完整校验再以单次 `AtomicReference`
  替换不可变 `RouteTable`，读者任何时刻只看到完整旧表或完整新表。
- **非法整批拒绝**：任一非法则整批不生效、旧表保留，400 并分类返回
  `EMPTY_BATCH / DUPLICATE_ROUTE_ID / INVALID_PATH_PREFIX / EMPTY_BACKENDS / DUPLICATE_BACKEND_ID`。
- **匹配规则**：最长路径前缀优先。

## 二、限流的维度与窗口规则（原有，保持不变）

- 维度 (路由, 调用方)；`"*"` 每值独立计数，`"@all"` 全体共享，其余精确匹配。
- 固定窗口自然时间片，窗口切换不重复计数、不漏计；`ConcurrentHashMap.compute` 原子计数不超发。
- 拒绝返回 429 `RATE_LIMITED`，携带命中规则 id。

## 三、粘性会话的失效与回切条件（原有，保持不变）

- 首次成功转发按 (路由, 调用方) 绑定，TTL 默认 300s；有效期内优先落绑定实例。
- 失效：超过 TTL；或绑定实例转发失败（仅当绑定仍指向它时才解除）。
- 切换：失败实例标记不健康后在剩余健康实例中重试，一个请求同一时刻只发往一个实例；
  全失败返回 503 `NO_HEALTHY_BACKEND`（列出尝试过的实例）。

---

## 七、本地复现与验证

### 1. 跑单测（7 个业务域）

```bash
mvn test
```

日志中的 `BUSINESS-COVERAGE` 行逐条打印 `[业务域:xxx] 用例:yyy => PASS/FAIL`，
并在每个测试类结束时给出每个业务域的用例数/通过数汇总：

| 测试类 | 业务域 | 用例数 | 覆盖要点 |
| --- | --- | --- | --- |
| RouteHotUpdateTest | 路由热更新 | 4 | 整批生效/整批拒绝/并发无中间态/空批次 |
| RateLimiterConcurrencyTest | 请求限流 | 4 | 并发不超发/窗口切换/拒绝可区分/调用方独立 |
| StickyFailoverTest | 会话粘性 | 4 | 同会话同实例/故障切换/全挂明确错误/恢复后回切 |
| RequestPassthroughTest | 请求透传 | 6 | 方法/头/查询/体透传、状态码与响应头回传、响应体 |
| InstanceRecoveryTest | 实例恢复 | 8 | 探活自动恢复、后台任务恢复、预热渐进、状态可观察、不误判、不打断在途、新粘性、TTL 自然回切、探活失败不影响他者 |
| LoadBalanceConcurrencyTest | 负载均衡与容量保护 | 8 | 权重分散、平滑交替、并发不超发、排队可得、超时可区分、OVERLOADED 分类、满了转其他实例 |
| HttpGatewayIntegrationTest | 端到端透传 | 6 | 真实 HTTP：全方法、透传（含表单 PUT/DELETE 请求体）、状态/头回传、404 错误头、/admin/health |

合计 41 个用例（含 Spring 上下文启动 1 个）。

### 2. 本地端到端复现

```bash
mvn compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"

# 两个模拟后端 + 网关（默认 8080；后端 18081/18082）
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend backend-1 18081 &
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend backend-2 18082 &
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.Application &
```

**请求透传（不同方法/头/查询/体原样到达；201 原样回传）：**

```bash
curl -s -X PUT  'localhost:8080/api/items/7?debug=true' -H 'X-Caller-Id: alice' \
     -H 'X-Custom: cv' -d 'hello-body'; echo
curl -s -i -X POST localhost:8080/api/status/201 -H 'X-Caller-Id: alice' -d x   # 状态行应为 HTTP/1.1 201
curl -s -X DELETE localhost:8080/api/items/9 -H 'X-Caller-Id: alice'; echo
```

**多实例分担（每次用不同调用方=不同新会话，按权重交替落不同实例；相同调用方则保持粘性）：**

```bash
for i in $(seq 1 6); do
  curl -s -X POST localhost:8080/api/hello -H "X-Caller-Id: user-$i" -d x \
    | sed 's/.*"backend":"\([^"]*\)".*/\1/'
done
# 等权两实例 -> backend-1 / backend-2 平滑交替
```

**实例自动恢复（杀 backend-1 -> 观察不健康 -> 重启 -> 观察自动 HEALTHY）：**

```bash
curl -s localhost:8080/admin/health                       # 初始都 HEALTHY
kill <backend-1 PID>
curl -s -X POST localhost:8080/api/hello -H 'X-Caller-Id: carol' -d hi   # 自动切到 backend-2
curl -s localhost:8080/admin/health                       # backend-1 UNHEALTHY
java -cp "$CP" com.github.feng111627.dynamicapigatewayjava.demo.FakeBackend backend-1 18081 &
# 约 1 秒（探测间隔）内自动恢复，无需改配置/重启；随后 5 秒预热内只渐进接新会话
watch -n 0.5 'curl -s localhost:8080/admin/health'
```

**并发容量保护（给 backend-1 配 maxConcurrency=1，打满后得到可区分拒绝）：**

```bash
curl -s -X PUT localhost:8080/admin/routes -H 'Content-Type: application/json' -d '{"routes":[
 {"id":"default","pathPrefix":"/api/","backends":[
   {"id":"backend-1","host":"127.0.0.1","port":18081,"weight":1,"maxConcurrency":1},
   {"id":"backend-2","host":"127.0.0.1","port":18082,"weight":1,"maxConcurrency":0}]}]}'

# 一个请求在 backend-1 的 /hold/ 上挂住（最多挂 30s）
curl -s -X POST localhost:8080/api/hold/x -H 'X-Caller-Id: h1' &
sleep 0.3
# 同一实例满了：acquire-timeout 内拿不到就转 backend-2（这里 backend-2 不限容量，所以成功且 backend=backend-2）
curl -s -X POST localhost:8080/api/echo/x -H 'X-Caller-Id: u2' -d x; echo
```

> 想直接看到 503 `BACKEND_OVERLOADED`：把两个实例都配成 `maxConcurrency:1`，
> 用两个不同 `X-Caller-Id` 各发一个 `/hold/` 请求挂住，再用第三个调用方请求即可得到
> 503 + `X-Gateway-Error-Kind: BACKEND_OVERLOADED`。

**路由热更新 / 限流 / 故障切换（原有验证方式）：**

```bash
# 热更新整批生效 / 非法整批拒绝
curl -s -X PUT localhost:8080/admin/routes -H 'Content-Type: application/json' -d '{"routes":[
 {"id":"r1","pathPrefix":"/api/","backends":[
   {"id":"backend-1","host":"127.0.0.1","port":18081},
   {"id":"backend-2","host":"127.0.0.1","port":18082}]},
 {"id":"r2","pathPrefix":"/api/v2/","backends":[
   {"id":"backend-2","host":"127.0.0.1","port":18082}]}]}'

# 限流：默认 60 次/分钟/调用方/路由，第 61 次起 429
for i in $(seq 1 65); do curl -s -o /dev/null -w '%{http_code} ' -X POST \
  localhost:8080/api/hello -H 'X-Caller-Id: dave' -d x; done; echo
```

### 3. 关键配置项（application.properties，均可 -D 覆盖）

```properties
gateway.session.ttl-millis=300000              # 粘性 TTL
gateway.backend1.weight=1                      # 实例权重
gateway.backend1.max-concurrency=0             # 单实例并发上限，0=不限
gateway.recovery.probe-interval-millis=1000    # 自动探活间隔
gateway.recovery.warmup-millis=5000            # 恢复后预热时长
gateway.recovery.share-divisor=4               # 预热期承接新会话比例 1/N
gateway.capacity.acquire-timeout-millis=200    # 并发打满后的排队等待上限
```
