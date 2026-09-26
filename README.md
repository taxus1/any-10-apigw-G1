# apigw · 微服务网关

Spring Cloud Gateway（WebFlux 响应式）+ Redis 动态路由配置。JDK 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.1。

同一个应用里跑两件事：

1. **转发链路**：请求进来 → 按配置的匹配条件找到路由 → 按配置的转发动作处理请求头 → 打到上游 → 响应回来按相反顺序处理响应头 → 交还调用方。
2. **管理接口**：`/api/gateway/routes`，维护路由及其匹配条件、转发动作。配置改完存 Redis，不用改配置文件重启（网关侧的自动热刷新是后续 F5 的题）。

## 起环境

```bash
docker compose up -d                # 起 Redis（路由配置存在这里）
mvn spring-boot:run                 # 网关，8080
bash tools/start-echo-upstream.sh   # 本地回显上游，8091（另开一个终端）
```

## 管理接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/gateway/routes` | 新建路由（连同条件与动作一起落库） |
| PUT | `/api/gateway/routes/{routeNo}` | 修改路由（整树替换，必须带 `version`） |
| GET | `/api/gateway/routes/{routeNo}` | 路由详情（含全部子项，按顺序号排好） |
| GET | `/api/gateway/routes?pageNum=&pageSize=&keyword=` | 分页列表（每条带条件/动作计数） |
| DELETE | `/api/gateway/routes/{routeNo}?expectVersion=` | 删除路由（整树清掉） |

所有接口返回统一结构 `{ code, msg, data }`：

- `code=0` 成功；
- 通用业务失败 `code=1`；
- 路由不存在 `code=404`（删除不存在的路由不算成功）；
- 并发冲突 `code=409`（你手上的版本旧了）。

### 请求体形状

```json
{
  "routeNo": "order-route",
  "name": "订单服务路由",
  "upstream": "http://order-svc:8080",
  "enabled": 1,
  "remark": "给前端下单用",
  "version": 0,
  "conditions": [
    { "type": "PATH_PREFIX", "value": "/order/", "sortNo": 1 },
    { "type": "METHOD", "value": "GET", "sortNo": 2 },
    { "type": "HEADER", "name": "X-Caller", "value": "web", "sortNo": 3 },
    { "type": "QUERY", "name": "from", "value": "cart", "sortNo": 4 }
  ],
  "actions": [
    { "type": "REQ_ADD_HEADER", "name": "X-Gw", "value": "1", "sortNo": 1 },
    { "type": "REQ_REMOVE_HEADER", "name": "X-Internal", "sortNo": 2 },
    { "type": "RESP_ADD_HEADER", "name": "X-Trace", "value": "t-1", "sortNo": 3 },
    { "type": "RESP_REMOVE_HEADER", "name": "X-Debug", "sortNo": 4 }
  ]
}
```

- 路由编号：业务唯一，建后**不可改**（PUT 的 body 里编号与路径不一致会被拦）；停用的路由也占号，只有删除才释放编号。
- 匹配条件只认 `PATH_PREFIX` / `METHOD` / `HEADER` / `QUERY`；路径、方法两类不用填 `name`。
- 转发动作只认 `REQ_ADD_HEADER` / `REQ_REMOVE_HEADER` / `RESP_ADD_HEADER` / `RESP_REMOVE_HEADER`；删头不用填 `value`。
- 顺序号每组各自从 1 开始，必须**连续、不重**。撞号会报「匹配条件第 a 条与第 b 条的顺序号撞了，都是 n」；跳号会报缺了第几。
- 上游地址必须是合法的 `http://` / `https://` URL（协议、主机、端口都像样），空串和乱码不收。
- 修改时把条件/动作整批重排提交即可，服务端按新一批顺序号整树替换。

### 分页返回

```json
{
  "code": 0,
  "data": {
    "content": [ { "routeNo": "...", "conditionCount": 2, "actionCount": 4, "...": "..." } ],
    "total": 37,
    "pageNum": 2,
    "pageSize": 20,
    "totalPages": 2
  }
}
```

- `pageNum` 从 1 开始；`pageSize` 上限 200（传 99999 也只按 200 算），防止一次拖全量。
- `keyword` 在编号和名称上做忽略大小写的模糊匹配。
- 列表每行直接带 `conditionCount` / `actionCount`，前端不用逐条再查。

## 配置怎么存

```
Redis key   apigw:routes          类型 Hash
            field = routeNo
            value = 该路由连同全部条件、动作的一整份 JSON
```

**为什么「一条路由 + 它的全部子项」塞在一个 field 里**：保存是一次 `HSET`、删除是一次 `HDEL`，Redis 单命令原子，所以「全落库或全不落」不需要手工回滚，也不可能读出主记录在、子项不在的残缺路由；删除时一次 `HDEL` 整树清掉，没有无主子记录可留。

## 并发怎么控

两层，都在 Redis 上：

1. **建路由占号用 `HSETNX`**：「查编号是否存在」和「写入」合成一个原子动作。两个人同时建同一个编号，只有一个成功，另一个收「编号已被占用（停用的路由也占号）」。
2. **改/删同一条用「短租约锁 + version 乐观锁」**：
   - 锁 key `apigw:lock:route:{routeNo}`，`SET NX` 带 5 秒 TTL，值是唯一 token，释放走 Lua 比对 token 后删除（不会误删别人的锁）；它把「读当前版本 → 写回」串成临界区。
   - 每条路由带 `version`：**修改必须显式带上读取时拿到的版本**（首版传 0），服务端比对一致才写、然后 version+1；不一致返回 `code=409`「你这份配置已经旧了（当前版本 n，你手上是 m），请重新拉取后再提交」。删除带 `expectVersion` 时有同样保护。
   - 不允许不带版本就改，否则等于把乐观锁绕过去、静默覆盖。

## 测试

```bash
docker compose up -d     # 提供真实 Redis
mvn test
```

- `GatewayRouteTest`：聚合不变量（编号不可改、上游地址、顺序号撞/跳并报位置、类型白名单、必填项），无需 Redis。
- `GatewayRouteControllerWebTest`：HTTP 切片（真实 Controller + AppService + 聚合，mock 掉 Redis），覆盖统一返回、报错文案、分页数字与子项计数。
- `RouteStoreTest` / `GatewayRouteControllerIT`：连真实 Redis，覆盖 HSETNX 原子占号、并发建同号、乐观锁 409、整树替换与级联删除。本机探测不到 `localhost:6379` 时自动跳过（可用 `-Dredis.host/-Dredis.port` 指向别处）。

## 已知边界（留给后续题目）

- 增删改后网关**不会自动热刷新**，当前需重启生效；多实例一致刷新是后续 F5 的题。
- 未匹配任何路由的请求目前落到静态资源处理，返回的不是规范的 404；这是后续 G2 的题。
- 管理接口未鉴权；接入鉴权与身份透传是后续 F2 的题。
