# apigw · 微服务网关

Spring Cloud Gateway（WebFlux 响应式）+ Redis 动态路由配置。JDK 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.1。

同一个应用里跑两件事：

1. **转发链路**：请求进来 → 按配置的匹配条件找到路由 → 按配置的转发动作处理请求头 → 打到上游 → 响应回来按相反顺序处理响应头 → 交还调用方。
2. **管理接口**：`/api/gateway/routes`，维护路由及其匹配条件、转发动作。

## 起环境

```bash
docker compose up -d          # 起 Redis（路由配置存在这里）
mvn spring-boot:run           # 网关，8080
bash tools/start-echo-upstream.sh   # 本地回显上游，8091（另开一个终端）
```

## 管理接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/gateway/routes` | 新建路由（连同条件与动作） |
| PUT | `/api/gateway/routes/{routeNo}` | 修改路由（body 必须带当前 `version`；编号留空取路径上的，与路径不一致则拒） |
| GET | `/api/gateway/routes/{routeNo}` | 路由详情（含全部子项） |
| GET | `/api/gateway/routes?pageNum=&pageSize=&keyword=` | 分页列表（带子项计数；`pageNum` 从 1 起，`pageSize` 上限 200，`keyword` 模糊匹配编号或名称） |
| DELETE | `/api/gateway/routes/{routeNo}?expectVersion=` | 删除路由（`expectVersion` 可选，带上可防误删别人刚改过的版本） |

所有接口返回统一结构 `{ code, msg, data }`，`code=0` 成功。校验失败时 `msg` 会指到具体子项，例如 `匹配条件第 2 条与第 1 条顺序号撞车（都是 1）`、`转发动作第 3 条的类型不支持：XX`。

增删改成功后，本网关实例会立即重新加载路由（`RefreshRoutesEvent`），不用重启。

## 路由配置的形状

一个路由：编号（建后不可改，字母数字与 `. _ -`）、名称、上游地址（必须是带 `http://`/`https://` 且主机名合法的 URL）、启用开关（1/0）、备注、版本号。

挂在它下面两组子项：

- **匹配条件**（`ruleKind=CONDITION`，恒作用于请求方向）：`PATH_PREFIX` / `METHOD` / `HEADER` / `QUERY`。
- **转发动作**（`ruleKind=ACTION`）：`REQ_ADD_HEADER` / `REQ_REMOVE_HEADER` / `RESP_ADD_HEADER` / `RESP_REMOVE_HEADER`。

每组子项带 `sortNo`，同一组内要求从 1 起、连续、不重。

## 存储结构（Redis）

```
key   apigw:routes          Hash
field routeNo
value 该路由及其全部子项的 JSON
```

编号级并发用一把短租约锁（`SET NX` + TTL）保护临界区，配合 `version` 字段做乐观锁：

- **新建**：查重在锁内，两人同时建同编号，后到的收到「路由编号已存在」；
- **修改**：必须带当前 `version`，两人同时改同一条，后到的收到「这条路由已被别人改过…请刷新后重试」；
- **删除**：路由不存在会明确报错，不会静默当成功；删的时候整个 field 一次移除，子项不会留孤儿。

一条路由及其全部子项就是同一个 field 的 JSON，一次 `HSET`/`HDEL` 落库，要么全成要么全不成，不存在半截数据。

## 已知边界（留给后续题目）

- 增删改后**本实例**立即生效；多实例部署时其它实例的广播刷新是后续 F5 的题。
- 未匹配任何路由的请求目前落到静态资源处理，返回的不是规范的 404；这是后续 G2 的题。
- 管理接口未鉴权；接入鉴权与身份透传是后续 F2 的题。
