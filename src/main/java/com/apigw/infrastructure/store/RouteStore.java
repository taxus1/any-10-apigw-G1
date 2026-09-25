package com.apigw.infrastructure.store;

import com.apigw.common.exception.BizException;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 路由配置在 Redis 里的读写封装。
 *
 * 存储结构（单 key 存全量，读改都走一份文档）：
 *   key   = apigw:routes               （Hash）
 *   field = routeNo
 *   value = 该路由及其全部子项的 JSON
 *
 * 为什么用 Hash 而不是一堆独立 key：
 * - 读全量只需一次 HGETALL，配置大量路由时仍是一次往返；
 * - 结构天然是「一份配置」，后面做原子切换 / 版本号时只动这个 key。
 *
 * 这里只负责序列化与并发控制，业务规则在 {@link GatewayRoute} 聚合里。
 */
@Component
public class RouteStore {

    public static final String ROUTES_KEY = "apigw:routes";

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final int LOCK_RETRY = 50;

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RouteStore(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 读全部路由（含子项）。 */
    public Flux<GatewayRoute> findAll() {
        return redis.opsForHash().values(ROUTES_KEY)
                .map(v -> deserialize(v.toString()));
    }

    /** 按编号读一条。 */
    public Mono<GatewayRoute> findByRouteNo(String routeNo) {
        return redis.opsForHash().get(ROUTES_KEY, routeNo)
                .map(v -> deserialize(v.toString()));
    }

    /** 编号是否已被占用（含停用；删除即真正移除，不占号）。 */
    public Mono<Boolean> existsByRouteNo(String routeNo) {
        return redis.opsForHash().hasKey(ROUTES_KEY, routeNo);
    }

    /**
     * 保存一条路由（整条覆盖）。
     *
     * 并发控制走一把短租约的 Redis 锁：读当前版本 → 比对期望版本 → 写入并版本 +1。
     * 两个人同时改同一条时，后到的那个拿到的版本已变，会收到「你这份旧了」。
     */
    public Mono<GatewayRoute> save(GatewayRoute route) {
        return withLock(route.getRouteNo(), () ->
                findByRouteNo(route.getRouteNo())
                        .map(existing -> {
                            checkVersion(existing, route);
                            route.setVersion(existing.getVersion() + 1);
                            return route;
                        })
                        .defaultIfEmpty(route)
                        .flatMap(r -> write(r).thenReturn(r)));
    }

    /** 删除一条路由，同样带版本比对。 */
    public Mono<Void> delete(String routeNo, Integer expectVersion) {
        return withLock(routeNo, () ->
                findByRouteNo(routeNo)
                        .switchIfEmpty(Mono.error(new BizException("路由不存在：" + routeNo)))
                        .flatMap(existing -> {
                            if (expectVersion != null && !expectVersion.equals(existing.getVersion())) {
                                return Mono.error(new BizException(
                                        "这条路由已被别人改过（当前版本 " + existing.getVersion()
                                                + "，你手上是 " + expectVersion + "），请刷新后重试"));
                            }
                            return redis.opsForHash().remove(ROUTES_KEY, routeNo).then();
                        }));
    }

    /** 用一份完整配置替换整个 Hash —— 给后续「配置整体刷新」留的原子入口。 */
    public Mono<Void> replaceAll(Map<String, GatewayRoute> routes) {
        Map<String, String> raw = new TreeMap<>();
        routes.forEach((k, v) -> raw.put(k, serialize(v)));
        return redis.delete(ROUTES_KEY)
                .then(redis.opsForHash().putAll(ROUTES_KEY, raw))
                .then();
    }

    private void checkVersion(GatewayRoute existing, GatewayRoute incoming) {
        Integer expect = incoming.getVersion();
        if (expect != null && !expect.equals(existing.getVersion())) {
            throw new BizException("这条路由已被别人改过（当前版本 " + existing.getVersion()
                    + "，你手上是 " + expect + "），请刷新后重试");
        }
    }

    private Mono<Void> write(GatewayRoute route) {
        return redis.opsForHash()
                .put(ROUTES_KEY, route.getRouteNo(), serialize(route))
                .then();
    }

    /**
     * 拿一把基于 Redis 的短租约锁（SET NX + TTL），保证「读版本 → 写回」这段是临界区。
     * 拿不到就小睡重试，超时抛业务异常，避免无限自旋。
     */
    private <T> Mono<T> withLock(String routeNo, java.util.function.Supplier<Mono<T>> action) {
        String lockKey = "apigw:lock:route:" + routeNo;
        return tryLock(lockKey, 0)
                .flatMap(acquired -> {
                    if (!acquired) {
                        return Mono.error(new BizException("这条路由正被另一个人修改，请稍后重试"));
                    }
                    return action.get().doFinally(sig ->
                            redis.opsForValue().delete(lockKey).subscribe());
                });
    }

    private Mono<Boolean> tryLock(String lockKey, int attempt) {
        return redis.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL)
                .flatMap(ok -> {
                    if (ok || attempt >= LOCK_RETRY) {
                        return Mono.just(ok);
                    }
                    return Mono.delay(Duration.ofMillis(20))
                            .then(tryLock(lockKey, attempt + 1));
                });
    }

    // ---- 序列化：不用 Java 原生序列化，存 JSON，便于人工排查与后续版本迁移 ----

    public String serialize(GatewayRoute route) {
        try {
            return objectMapper.writeValueAsString(Dto.from(route));
        } catch (Exception e) {
            throw new BizException("路由序列化失败：" + e.getMessage());
        }
    }

    public GatewayRoute deserialize(String json) {
        try {
            Dto dto = objectMapper.readValue(json, new TypeReference<Dto>() {
            });
            return dto.toDomain();
        } catch (Exception e) {
            throw new BizException("路由反序列化失败：" + e.getMessage());
        }
    }

    /** 落 Redis 的形状：字段与领域对象一致，直接复用领域模型的公开 getter/setter。 */
    public static class Dto {
        public String id;
        public String routeNo;
        public String name;
        public String upstream;
        public Integer enabled;
        public String remark;
        public Integer version;
        public List<RuleDto> conditions = new ArrayList<>();
        public List<RuleDto> actions = new ArrayList<>();

        static Dto from(GatewayRoute r) {
            Dto d = new Dto();
            d.id = r.getId();
            d.routeNo = r.getRouteNo();
            d.name = r.getName();
            d.upstream = r.getUpstream();
            d.enabled = r.getEnabled();
            d.remark = r.getRemark();
            d.version = r.getVersion();
            d.conditions = r.getConditions().stream().map(RuleDto::from).toList();
            d.actions = r.getActions().stream().map(RuleDto::from).toList();
            return d;
        }

        GatewayRoute toDomain() {
            GatewayRoute r = GatewayRoute.create(routeNo, name, upstream, enabled, remark);
            r.setId(id);
            r.setVersion(version == null ? 0 : version);
            r.replaceRules(
                    conditions == null ? List.of() : conditions.stream().map(RuleDto::toDomain).toList(),
                    actions == null ? List.of() : actions.stream().map(RuleDto::toDomain).toList());
            r.getConditions().forEach(x -> x.setRuleKind(RuleTypes.KIND_CONDITION));
            r.getActions().forEach(x -> x.setRuleKind(RuleTypes.KIND_ACTION));
            return r;
        }
    }

    /** 子项在 Redis 里的形状。 */
    public static class RuleDto {
        public String id;
        public String stage;
        public String type;
        public String name;
        public String value;
        public Integer sortNo;

        static RuleDto from(GatewayRule g) {
            RuleDto d = new RuleDto();
            d.id = g.getId();
            d.stage = g.getStage();
            d.type = g.getType();
            d.name = g.getName();
            d.value = g.getValue();
            d.sortNo = g.getSortNo();
            return d;
        }

        GatewayRule toDomain() {
            return GatewayRule.create(stage, type, name, value, sortNo);
        }
    }

    /** 按顺序号排好的子项（对外返回时统一排序）。 */
    public static List<GatewayRule> sorted(List<GatewayRule> rules) {
        List<GatewayRule> copy = new ArrayList<>(rules);
        copy.sort(Comparator.comparing(GatewayRule::getSortNo, Comparator.nullsLast(Integer::compareTo)));
        return copy;
    }

    /** 供管理接口做「按编号模糊找」用：把 keyword 归一化，空串视为不过滤。 */
    public static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String k = keyword.trim();
        return k.isEmpty() ? null : k;
    }

    /** 编号集合，供统计用。 */
    public Mono<Set<String>> allRouteNos() {
        return redis.opsForHash().keys(ROUTES_KEY).collectList()
                .map(list -> new java.util.HashSet<>(list.stream().map(Object::toString).toList()));
    }
}
