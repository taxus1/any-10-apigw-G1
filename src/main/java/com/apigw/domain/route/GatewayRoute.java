package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合根：一条网关路由，连同它的全部匹配条件与转发动作。
 *
 * 聚合不变量（本类负责守住）：
 * 1. routeNo 建后不可改，且只允许字母数字与 . _ -；
 * 2. upstream 必须是带 http/https 协议且主机名合法的 URL；
 * 3. 同一 kind 的子项顺序号必须从 1 起、连续、不重；
 * 4. 类型/方向/必填项由 {@link GatewayRule#validateAs} 守住。
 *
 * version 承载乐观锁语义：并发保存同一条路由时，旧版本提交会被拒。
 */
@Getter
@Setter
public class GatewayRoute {

    private String id;

    /** 路由编号，业务唯一，建后不可改。 */
    private String routeNo;

    private String name;

    /** 上游地址，如 http://order-svc:8080。 */
    private String upstream;

    /** 1 启用 / 0 停用。 */
    private Integer enabled;

    private String remark;

    /** 乐观锁版本号。 */
    private Integer version;

    /** 匹配条件（stage 恒为 REQUEST）。 */
    private List<GatewayRule> conditions = new ArrayList<>();

    /** 转发动作（stage 可为 REQUEST 或 RESPONSE）。 */
    private List<GatewayRule> actions = new ArrayList<>();

    public static GatewayRoute create(String routeNo, String name, String upstream,
                                      Integer enabled, String remark) {
        GatewayRoute route = new GatewayRoute();
        route.assignRouteNo(routeNo);
        route.rename(name);
        route.changeUpstream(upstream);
        route.setEnabled(enabled == null ? 1 : enabled);
        route.setRemark(remark);
        route.setVersion(0);
        route.setConditions(new ArrayList<>());
        route.setActions(new ArrayList<>());
        return route;
    }

    /** 建号：非空、格式合法、且建后不可修改。 */
    public void assignRouteNo(String routeNo) {
        if (routeNo == null || routeNo.isBlank()) {
            throw new BizException("路由编号不能为空");
        }
        String v = routeNo.trim();
        if (!v.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new BizException("路由编号只能包含字母、数字、点、下划线、短横，最长 64 位");
        }
        if (this.routeNo != null && !this.routeNo.equals(v)) {
            throw new BizException("路由编号建后不可修改");
        }
        this.routeNo = v;
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("路由名称不能为空");
        }
        this.name = name.trim();
    }

    /** 改上游：必须是带 http/https 协议、且主机名解析得出的合法 URL，乱码不收。 */
    public void changeUpstream(String upstream) {
        if (upstream == null || upstream.isBlank()) {
            throw new BizException("上游地址不能为空");
        }
        String v = upstream.trim();
        URI uri;
        try {
            uri = new URI(v);
        } catch (URISyntaxException e) {
            throw new BizException("上游地址不是合法 URL：" + v);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BizException("上游地址必须以 http:// 或 https:// 开头");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BizException("上游地址缺主机名或主机名不合法：" + v);
        }
        this.upstream = v;
    }

    /** 启用开关只认 1（启用）/ 0（停用），其余一律挡回。 */
    public void setEnabled(Integer enabled) {
        if (enabled != null && enabled != 0 && enabled != 1) {
            throw new BizException("启用开关只能是 1（启用）或 0（停用），收到：" + enabled);
        }
        this.enabled = enabled;
    }

    /**
     * 校验整组子项并挂到聚合上。两个列表都是「整树替换」语义：
     * 保存时先清旧子项、再整批落新的，避免增量合并留下孤儿。
     */
    public void replaceRules(List<GatewayRule> newConditions, List<GatewayRule> newActions) {
        List<GatewayRule> cs = newConditions == null ? new ArrayList<>() : new ArrayList<>(newConditions);
        List<GatewayRule> as = newActions == null ? new ArrayList<>() : new ArrayList<>(newActions);
        validateKind(cs, RuleTypes.KIND_CONDITION);
        validateKind(as, RuleTypes.KIND_ACTION);
        this.conditions = cs;
        this.actions = as;
    }

    /** 同 kind 的顺序号必须从 1 起、连续、不重；每条自身也要通过类型校验。 */
    private void validateKind(List<GatewayRule> rules, String kind) {
        String label = RuleTypes.KIND_CONDITION.equals(kind) ? "匹配条件" : "转发动作";
        // 顺序号 → 首次出现的位次，撞车时能指出是哪两条
        Map<Integer, Integer> firstSeenAt = new HashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            GatewayRule r = rules.get(i);
            if (r == null) {
                throw new BizException(label + "第 " + (i + 1) + " 条为空");
            }
            r.validateAs(kind, i + 1);
            Integer prev = firstSeenAt.putIfAbsent(r.getSortNo(), i + 1);
            if (prev != null) {
                throw new BizException(label + "第 " + (i + 1) + " 条与第 " + prev
                        + " 条顺序号撞车（都是 " + r.getSortNo() + "），同一路由内顺序号不能重复");
            }
        }
        for (int n = 1; n <= rules.size(); n++) {
            if (!firstSeenAt.containsKey(n)) {
                throw new BizException(label + "的顺序号必须从 1 起连续，缺了 " + n);
            }
        }
    }
}
