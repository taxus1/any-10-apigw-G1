package com.apigw.application.route;

import com.apigw.common.exception.BizException;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.infrastructure.store.dto.RouteView;
import com.apigw.infrastructure.store.dto.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 路由配置的应用服务：编排用例、守住事务/一致性边界。
 *
 * 业务规则仍放在 {@link GatewayRoute} 聚合里，这里只做：
 * - 「整树保存」的编排：聚合校验 → 编号查重 → 一次写入；
 * - 分页与关键字过滤（读多写少，过滤在内存里做，避免为模糊查引入额外索引）；
 * - 列表需要的子项计数，在这里一次算好，不让前端挨个再查。
 */
@Service
public class GatewayRouteAppService {

    private static final int MAX_PAGE_SIZE = 200;

    private final RouteStore routeStore;

    public GatewayRouteAppService(RouteStore routeStore) {
        this.routeStore = routeStore;
    }

    /** 新建：编号查重 → 分配业务主键 → 写入。 */
    public Mono<GatewayRoute> create(GatewayRoute input) {
        return routeStore.existsByRouteNo(input.getRouteNo())
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.error(new BizException("路由编号已存在：" + input.getRouteNo()));
                    }
                    if (input.getId() == null) {
                        input.setId(UUID.randomUUID().toString().replace("-", ""));
                    }
                    return routeStore.save(input);
                });
    }

    /** 修改：编号不可改（由聚合兜底），版本比对交给 store。 */
    public Mono<GatewayRoute> update(String routeNo, GatewayRoute input) {
        return routeStore.findByRouteNo(routeNo)
                .switchIfEmpty(Mono.error(new BizException("路由不存在：" + routeNo)))
                .flatMap(existing -> {
                    // 编号以路径参数为准，传入体里的编号若与之不同，聚合会直接拒掉
                    input.assignRouteNo(routeNo);
                    input.setId(existing.getId());
                    return routeStore.save(input);
                });
    }

    public Mono<GatewayRoute> detail(String routeNo) {
        return routeStore.findByRouteNo(routeNo)
                .switchIfEmpty(Mono.error(new BizException("路由不存在：" + routeNo)));
    }

    public Mono<Void> delete(String routeNo, Integer expectVersion) {
        return routeStore.delete(routeNo, expectVersion);
    }

    /**
     * 分页列表：先按关键字过滤（编号或名称，忽略大小写），再按编号稳定排序，最后切片。
     * 列表项带 conditionCount / actionCount，前端不用二次查询。
     */
    public Mono<PageResult<RouteView>> page(int pageNum, int pageSize, String keyword) {
        int pn = pageNum < 1 ? 1 : pageNum;
        int ps = pageSize < 1 ? 20 : Math.min(pageSize, MAX_PAGE_SIZE);
        String kw = RouteStore.normalizeKeyword(keyword);

        return routeStore.findAll()
                .filter(r -> kw == null || matches(r, kw))
                .sort(Comparator.comparing(GatewayRoute::getRouteNo))
                .map(RouteView::of)
                .collectList()
                .map(list -> {
                    long total = list.size();
                    int from = Math.min((pn - 1) * ps, list.size());
                    int to = Math.min(from + ps, list.size());
                    List<RouteView> slice = list.subList(from, to);
                    return new PageResult<>(slice, total, pn, ps);
                });
    }

    private boolean matches(GatewayRoute r, String kw) {
        String lower = kw.toLowerCase(Locale.ROOT);
        return (r.getRouteNo() != null && r.getRouteNo().toLowerCase(Locale.ROOT).contains(lower))
                || (r.getName() != null && r.getName().toLowerCase(Locale.ROOT).contains(lower));
    }

    /** 把一份外部输入整理成聚合（校验在其中完成）。 */
    public GatewayRoute assemble(String routeNo, String name, String upstream, Integer enabled,
                                 String remark, Integer version,
                                 List<GatewayRule> conditions, List<GatewayRule> actions) {
        GatewayRoute route = GatewayRoute.create(routeNo, name, upstream, enabled, remark);
        route.setVersion(version == null ? 0 : version);
        route.replaceRules(conditions, actions);
        return route;
    }
}
