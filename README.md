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
| PUT | `/api/gateway/routes/{routeNo}` | 修改路由 |
| GET | `/api/gateway/routes/{routeNo}` | 路由详情（含全部子项） |
| GET | `/api/gateway/routes?pageNum=&pageSize=&keyword=` | 分页列表（带子项计数） |
| DELETE | `/api/gateway/routes/{routeNo}?expectVersion=` | 删除路由 |

所有接口返回统一结构 `{ code, msg, data }`，`code=0` 成功。

## 路由配置的形状

一个路由：编号（建后不可改）、名称、上游地址（`http://` 或 `https://` 开头）、启用开关（1/0）、备注、版本号。

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

编号级并发用一把短租约锁（`SET NX` + TTL）保护「读版本 → 写回」，配合 `version` 字段做乐观锁：两个人改同一条时，后到的那次会被拒，提示「你这份旧了」。

## 已知边界（留给后续题目）

- 增删改后网关**不会自动重载**，需要重启；动态刷新（多实例一致）是后续 F5 的题。
- 未匹配任何路由的请求目前落到静态资源处理，返回的不是规范的 404；这是后续 G2 的题。
- 管理接口未鉴权；接入鉴权与身份透传是后续 F2 的题。
