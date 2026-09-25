package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 聚合根：一条网关路由，连同它的全部匹配条件与转发动作。
 *
 * 聚合不变量（本类负责守住）：
 * 1. routeNo 建后不可改，且只允许字母数字与 . _ -；
 * 2. upstream 必须以 http:// 或 https:// 开头；
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

    /** 改上游：必须带 http:// 或 https:// 前缀，纯主机名不算数。 */
    public void changeUpstream(String upstream) {
        if (upstream == null || upstream.isBlank()) {
            throw new BizException("上游地址不能为空");
        }
        String v = upstream.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            throw new BizException("上游地址必须以 http:// 或 https:// 开头");
        }
        this.upstream = v;
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
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            GatewayRule r = rules.get(i);
            if (r == null) {
                throw new BizException("第 " + (i + 1) + " 条子项为空");
            }
            r.validateAs(kind, i + 1);
            if (!seen.add(r.getSortNo())) {
                throw new BizException("顺序号 " + r.getSortNo() + " 重复，同一路由内顺序号不能撞车");
            }
        }
        for (int n = 1; n <= rules.size(); n++) {
            if (!seen.contains(n)) {
                throw new BizException("顺序号必须从 1 起连续，缺了 " + n);
            }
        }
    }
}
