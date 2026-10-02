# Dynamic API Gateway（本地可验证版）

一个零外部依赖（只用 Spring Boot Web + JDK）的本地 API 网关核心实现，覆盖三大能力：

1. **路由转发 + 规则热更新**：按路径前缀转发到不同本地后端，规则运行期整批热替换，全生效或全不生效。
2. **多维限流**：按「路由」和「调用方」两个维度的固定窗口限流，并发下精确不超发，窗口切换不重不漏。
3. **会话粘性 + 故障切换**：同一调用方在会话期内固定落同一实例；实例故障自动切换，恢复后可控回切；请求不重发、不静默丢弃。

并自带一个**进程内本地后端模拟器**（JDK `HttpServer`），一台机器、一条命令即可复现真实流量分发。

---

## 1. 快速开始

需要 JDK 21+。

```bash
# 启动：网关 18080，两个模拟后端 18081 / 18082
mvn spring-boot:run -Dspring-boot.run.profiles=demo

# 另开一个终端，跑全自动冒烟脚本（转发/粘性/限流/故障/回切/热更新）
./demo/smoke.sh
```

冒烟脚本依赖的端口可用环境变量覆盖：`GW` / `B1` / `B2`。

不想用脚本也可以手动验证（以下命令全部可直接复制）：

```bash
# 转发（响应里的 id 是实际处理请求的后端实例）
curl -s -H 'X-Caller-Id: alice' http://127.0.0.1:18080/orders/42

# 会话粘性：连续多次都落在同一个实例（响应头 X-Gateway-Instance）
for i in 1 2 3; do curl -s -D - -H 'X-Caller-Id: alice' http://127.0.0.1:18080/orders/42 -o /dev/null | grep X-Gateway-Instance; done

# 限流：orders 路由每调用方 5 次/10 秒，第 6 次返回 429
for i in $(seq 1 6); do curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Caller-Id: rl' http://127.0.0.1:18080/orders/$i; done

# 故障切换：先确认某调用方落在哪个实例，再把它下线，下一次请求会在同一请求内自动切走
curl -s -X POST -d down http://127.0.0.1:18082/control          # backend-2 模拟宕机（健康检查返回 503）
curl -s -D - -H 'X-Caller-Id: alice' http://127.0.0.1:18080/orders/42 -o /dev/null | grep X-Gateway-Attempted
curl -s http://127.0.0.1:18080/admin/health                    # backend-2 应变为 UNHEALTHY
curl -s -X POST -d up   http://127.0.0.1:18082/control          # 恢复，探活成功后可回切
```

热更新接口（见第 4 节语义说明）：

```bash
# 查看当前生效配置（含版本号）
curl -s http://127.0.0.1:18080/admin/config

# 整批替换：合法 -> 200；非法 -> 422 并返回全部问题码，旧规则保留
curl -s -X PUT -H 'Content-Type: application/json' --data @demo/routes-v2.json      http://127.0.0.1:18080/admin/config
curl -s -X PUT -H 'Content-Type: application/json' --data @demo/routes-invalid.json http://127.0.0.1:18080/admin/config
```

---

## 2. 架构与模块

```
HTTP 请求
  │
  ▼
GatewayController            适配 Servlet 请求/响应，翻译结果为可区分的 HTTP 状态与响应头
  │
  ▼
GatewayDispatcher            编排：路由匹配 → 限流 → 选实例（含粘性/回切）→ 转发与故障切换
  │            │                 每个请求最终都有明确结果，单次请求内同一实例最多发一次
  │            ├── RouteRegistry        不可变快照 + 读写锁，整批原子发布（version 单调递增）
  │            ├── RateLimiter          固定窗口，ConcurrentHashMap.compute 原子计数
  │            ├── StickySessionStore   (route,caller) → 实例绑定，TTL 滑动，原始实例记忆
  │            ├── HealthManager        被动熔断 + 主动探活，状态带变更时间戳
  │            ├── RoundRobinSelector   线程安全轮询
  │            └── BackendForwarder     JDK HttpClient，传输错误不外抛
  ▼
本地后端实例（BackendSimulator，JDK HttpServer）
   GET  /health        200/503    供主动探活
   *    /*             回显实例 id 与请求序号
   POST /control       up/down    模拟宕机/恢复（不关 socket，返回 503）
   GET  /metrics       请求计数
```

管理面 `AdminController` 提供 `GET/PUT /admin/config`、`GET /admin/health`。
启动时 `BootstrapConfigLoader` 在模拟器监听后，以**同一条原子更新路径**装载演示路由并启动探活。

---

## 3. 路由配置长什么样

```json
{
  "routes": [
    {
      "id": "orders",
      "pathPrefix": "/orders",
      "stickySession": true,
      "rateLimit": { "routeLimit": 20, "callerLimit": 5, "windowSeconds": 10 },
      "backends": [
        { "id": "backend-1", "baseUrl": "http://127.0.0.1:18081" },
        { "id": "backend-2", "baseUrl": "http://127.0.0.1:18082" }
      ]
    }
  ]
}
```

- `pathPrefix`：最长前缀匹配，`/orders/special/9` 优先命中更具体的 `/orders/special`。
- 同一 `backend.id` 可被多条路由共享，但映射到不同 `baseUrl` 会被判为冲突（`BACKEND_CONFLICT`）。
- 请求中的调用方身份取自请求头 `X-Caller-Id`（可用 `gateway.caller-header` 改）。

---

## 4. 路由热更新的生效语义

更新接口：`PUT /admin/config`，请求体为**全量**新规则（客户端无需、也无法提交"差量"）。

**原子性（全有或全无）**

1. 候选配置先做**整批独立校验**，收集全部问题；校验期间不触碰线上快照。
2. 只要有任何非法项：返回 `422`，线上配置一行不改、版本号不前进；同批里哪怕只有一条非法，其余合法条目也**不会**半生效。
3. 全部合法时：构建一个新的不可变 `GatewayConfig`，版本号 +1，通过**一次 volatile 发布**替换旧快照；限流计数清零、健康实例表同步等提交副作用在**同一把写锁内**紧跟发布执行。
4. 请求线程先取一次快照引用再匹配，因此在更新前后只可能看到"旧的完整规则集"或"新的完整规则集"，永远看不到中间态、也不会出现某条路由突然消失。

**调用方可区分的错误分类**

| HTTP | code / 场景 | 含义 |
| --- | --- | --- |
| `400` | `REQUEST_BODY_INVALID` | 请求体本身坏（JSON 解析失败/结构不对），不是"规则非法" |
| `422` | 规则问题码列表 | 请求体合法但规则非法，整批拒绝、旧规则保留 |
| `200` | `accepted:true, version:n` | 整批已生效 |

422 返回的 `issues[]` 中每条都带稳定错误码，可直接据此分支处理：

| 错误码 | 触发条件 |
| --- | --- |
| `EMPTY_ROUTE_SET` | 空批次（防止误操作清空全部路由） |
| `ROUTE_ID_MISSING` / `DUPLICATE_ROUTE_ID` | 路由 id 缺失 / 批次内重复 |
| `PATH_PREFIX_MISSING` / `PATH_PREFIX_INVALID` | 前缀缺失 / 不以 `/` 开头 |
| `BACKEND_EMPTY` / `BACKEND_ID_MISSING` / `DUPLICATE_BACKEND_ID` | 后端为空、id 缺失、路由内重复 |
| `BACKEND_URL_INVALID` | baseUrl 非法（非 http(s) 或缺 host/port） |
| `BACKEND_CONFLICT` | 同一 backend id 跨路由映射到不同地址 |
| `RATE_LIMIT_WINDOW_MISSING` / `RATE_LIMIT_LIMIT_INVALID` / `RATE_LIMIT_WINDOW_INVALID` | 限流参数缺窗口、配额非正、窗口越界（1~3600s） |

成功响应示例：

```json
{ "accepted": true, "version": 2, "routeCount": 2,
  "message": "whole batch applied atomically; previous version fully replaced" }
```

---

## 5. 限流：维度与窗口规则

- **维度（相互独立计数）**
  - 路由维度：`routeLimit`，限制该路由的总 QPM（按窗口）。
  - 调用方维度：`callerLimit`，限制该路由下**每个调用方**（按 `X-Caller-Id`）各自的配额；无调用方头的匿名请求不参与调用方维度限流。
- **窗口**：固定窗口，`windowSeconds` 为窗口长度，窗口按绝对时间对齐切分（不是滑动起点）。
- **判定顺序**：先路由维度、后调用方维度，第一个拒绝即返回，因此响应能明确指出是被**哪条规则**拦下的。
- **并发正确性**
  - "读旧窗口 → 窗口到期则开新窗口 → 计数 +1" 整体在 `ConcurrentHashMap.compute` 内按 key 原子完成：并发线程在该 key 上串行化，**配额绝不超发**。
  - 窗口边界上恰好一个线程创建新窗口，旧窗口计数不残留（不重复计数），边界后的第一个请求计入新窗口（不漏计）。
- **热更新联动**：规则整批提交成功的同一把锁内清零全部窗口，新规则从干净计数开始。另有过期窗口定期清理，避免 map 无限增长。

**拒绝结果如何与其他错误区分**

- HTTP `429`；响应头：`X-RateLimit-Scope`（`ROUTE`/`CALLER`）、`X-RateLimit-Route`、`X-RateLimit-Caller`、`X-RateLimit-Limit`、`X-RateLimit-Window-Seconds`、`X-RateLimit-Used`、`Retry-After`。
- JSON 体：`code=RATE_LIMITED`，并带完整 `rateLimitRule` 段（维度、路由、调用方、配额、已用量、窗口起点）。

429 示例：

```json
{ "status": "RATE_LIMITED", "code": "RATE_LIMITED",
  "message": "rejected by rate-limit rule 'caller:alice' (CALLER): limit=5/window used=6",
  "routeId": "orders",
  "rateLimitRule": { "scope": "CALLER", "routeId": "orders", "callerId": "alice",
                     "limit": 5, "windowSeconds": 10, "used": 6, "windowStartMs": 1790964570000 } }
```

---

## 6. 会话粘性：失效与回切条件

- **绑定**：`stickySession:true` 的路由，按 `(routeId, callerId)` 绑定后端实例；非粘性路由走轮询。首次绑定使用轮询。
- **保持**：绑定有效期内（默认 `gateway.sticky-ttl-ms=60000`），且实例健康时，请求固定落该实例；每次成功请求会**滑动续期** TTL（活跃会话不会因为持续使用而中途失效）。
- **粘性失效（重新选实例）的条件**
  1. TTL 到期（自最后一次成功请求起超过 TTL 没有活动）；
  2. 绑定实例故障（见下），切换到健康实例后重新绑定；
  3. 规则热更新（整批替换，限流/粘性状态重置）。
- **故障切换**
  - 被动信号：连接失败/超时（传输错误）或上游 5xx 连续达到 `gateway.unhealthy-threshold`（默认 1）即熔断。
  - 请求当次即在请求内尝试其他健康实例并返回成功，不会等下一次请求才切。
  - **一次请求对任一实例最多发送一次**：尝试顺序在请求开始时规划且去重；即使切换途中健康状态变化，也不会把同一实例发两遍。
  - 非幂等方法（POST/PUT/DELETE/PATCH）收到上游 5xx **不重试**，直接把该 5xx 作为确定结果返回（避免重复写入）；传输错误（请求未到达/无响应）仍会切换。GET/HEAD 的 5xx 可切换，可由 `gateway.retry-get-on-5xx` 关闭。
  - 没有健康实例时返回明确结果：`502 NO_HEALTHY_INSTANCE` 或 `502 ALL_INSTANCES_FAILED`，响应体带 `attemptedInstances`，绝不无声丢请求。
- **恢复与回切**
  - 主动探活：每 `gateway.health-probe-interval-ms`（demo 1s）请求各实例 `/health`；连续成功 `gateway.healthy-threshold`（默认 1）次即转回 `HEALTHY`。
  - 回切防抖：原实例恢复后，必须先经过 `gateway.sticky-reattach-backoff-ms`（默认 1s）冷却，粘性会话才会切回原实例，避免刚恢复就抖动；冷却期内继续留在故障切换目标上。
  - 回切成功后保持稳定，并通过响应结果与响应头（`X-Gateway-Instance`、`X-Gateway-Attempted`、`X-Gateway-Result`）可观测。

---

## 7. 结果状态一览（每个请求必有明确结果）

| status / code | HTTP | 含义 |
| --- | --- | --- |
| `OK` / `UPSTREAM_RESPONSE` | 上游状态码 | 转发成功（上游 4xx/5xx 原样透传时也是明确结果） |
| `UPSTREAM_UNAVAILABLE` | 502 | 非幂等请求遇到传输错误，未切换 |
| `UPSTREAM_ERROR` | 上游 5xx | 非幂等请求遇 5xx，按语义不重试 |
| `NO_ROUTE` | 404 | 无路由匹配 |
| `RATE_LIMITED` | 429 | 被命名的限流规则拒绝（见响应头/体） |
| `NO_HEALTHY_INSTANCE` | 502 | 路由匹配但无健康实例（未发送任何请求） |
| `ALL_INSTANCES_FAILED` | 502 | 候选实例都试过且都失败，列出尝试过的实例 |

所有转发类响应都会带：`X-Gateway-Route`、`X-Gateway-Instance`（最终处理实例）、`X-Gateway-Attempted`（本次请求尝试序列）、`X-Gateway-Result`。

---

## 8. 测试与日志

```bash
mvn test          # 32 个用例
```

日志里每个业务域都有横幅，且逐用例打印"用例开始 / 用例通过"，可直接看出覆盖了哪些业务、每个业务的用例及通过情况：

```
[业务:路由热更新原子性] 业务覆盖：规则整批热更新（全生效/全不生效）
[业务:路由热更新原子性] >>> 用例开始: 批次中任意一条非法则整批拒绝，旧规则一条不少地保留
[业务:路由热更新原子性] <<< 用例通过: ...
[业务:多维限流并发正确性] 业务覆盖：按路由 + 按调用方维度的固定窗口限流
[业务:会话粘性与故障切换] 业务覆盖：粘性绑定、故障切换、恢复回切、请求不丢不重发
[业务:端到端HTTP链路]   业务覆盖：真实 HTTP 热更新 / 故障切换 / 恢复回切 / 限流
```

| 测试类 | 用例数 | 覆盖的关键场景 |
| --- | --- | --- |
| `RouteHotUpdateAtomicityTest` | 8 | 整批生效/整批拒绝、版本单调、旧规则完整保留、错误码可分类、空批次拒绝、跨路由 backend 冲突、最长前缀、提交钩子仅成功执行一次、8 读 4 写并发下只观察到完整快照 |
| `RateLimiterConcurrencyTest` | 7 | 配额精确放行、拒绝结果标明规则、调用方独立计数、32 线程抢 1000 配额精确不超发、10 调用方×8 并发各自不超发、窗口边界切换不重不漏、reset/过期清理 |
| `StickyFailoverTest` | 9 | 粘性保持、传输错误同请求切换、GET 5xx 切换、POST 5xx 不切换不重发、全实例故障 502、无健康实例 502、恢复+冷却回切、TTL 失效重选、16 并发故障切换无重发无丢失 |
| `GatewayEndToEndTest` | 7 | 真实 HTTP 全链路：演示配置加载、粘性、503 故障切换、探活恢复回切、连接拒绝、热更新 200/422/400、429 规则身份 |
| `ApplicationTests` | 1 | Spring 上下文装配冒烟 |

> 端到端测试自己在随机端口起网关、在 19081/19082 起模拟器，与 demo 手工验证互不影响。

---

## 9. 配置项（`application*.properties`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `gateway.caller-header` | `X-Caller-Id` | 调用方身份请求头 |
| `gateway.backend-simulator.enabled` | false | 是否启动进程内后端模拟器（demo 开启） |
| `gateway.backend-simulator.base-port` / `instances` | 18081 / 2 | 模拟器起始端口与实例数 |
| `gateway.health-probe-interval-ms` | 2000 | 主动探活间隔；<=0 关闭 |
| `gateway.unhealthy-threshold` / `healthy-threshold` | 1 / 1 | 被动连续失败熔断阈值 / 探活连续成功恢复阈值 |
| `gateway.sticky-ttl-ms` | 60000 | 粘性会话有效期（成功请求滑动续期） |
| `gateway.sticky-reattach-backoff-ms` | 1000 | 原实例恢复后允许回切前的防抖冷却 |
| `gateway.forward-timeout-ms` | 5000 | 连接与请求超时 |
| `gateway.retry-get-on-5xx` | true | GET/HEAD 遇上游 5xx 是否故障切换 |
| `gateway.demo-route-limit` / `demo-caller-limit` / `demo-window-seconds` | 100000 / 100000 / 10 | 启动时演示路由的限流配额（demo profile 收紧为 20/5/10s） |
