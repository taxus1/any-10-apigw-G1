package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 路由聚合的不变量测试（不依赖 Spring 容器）。
 * 覆盖：编号不可改、上游协议、顺序号连续不重、类型白名单、动作方向自洽、必填项。
 */
class GatewayRouteTest {

    private GatewayRoute base() {
        return GatewayRoute.create("order-route", "订单服务路由", "http://order-svc:8080", 1, null);
    }

    @Test
    void create_setsDefaults() {
        GatewayRoute r = base();
        assertEquals("order-route", r.getRouteNo());
        assertEquals(1, r.getEnabled());
        assertEquals(0, r.getVersion());
        assertEquals(0, r.getConditions().size());
        assertEquals(0, r.getActions().size());
    }

    @Test
    void routeNo_immutableAfterCreate() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.assignRouteNo("renamed"));
        assertEquals("路由编号建后不可修改", e.getMessage());
    }

    @Test
    void routeNo_rejectsBlankAndIllegal() {
        assertThrows(BizException.class, () -> GatewayRoute.create(" ", "n", "http://h:1", 1, null));
        assertThrows(BizException.class, () -> GatewayRoute.create("a/b", "n", "http://h:1", 1, null));
    }

    @Test
    void upstream_requiresProtocol() {
        BizException e = assertThrows(BizException.class,
                () -> GatewayRoute.create("r1", "n", "order-svc:8080", 1, null));
        assertEquals("上游地址必须以 http:// 或 https:// 开头", e.getMessage());
    }

    @Test
    void upstream_rejectsGarbage() {
        // 空串
        assertThrows(BizException.class, () -> GatewayRoute.create("r1", "n", "  ", 1, null));
        // 只有协议头，没有主机
        assertThrows(BizException.class, () -> GatewayRoute.create("r1", "n", "http://", 1, null));
        // 主机名是乱码符号
        assertThrows(BizException.class, () -> GatewayRoute.create("r1", "n", "http://@@##", 1, null));
        // 中间带空格的非法 URL
        assertThrows(BizException.class, () -> GatewayRoute.create("r1", "n", "ht tp://x", 1, null));
        // 非 http 协议
        assertThrows(BizException.class, () -> GatewayRoute.create("r1", "n", "ftp://order-svc:21", 1, null));
    }

    @Test
    void enabled_onlyZeroOrOne() {
        BizException e = assertThrows(BizException.class,
                () -> GatewayRoute.create("r1", "n", "http://h:1", 7, null));
        assertEquals("启用开关只能是 1（启用）或 0（停用），收到：7", e.getMessage());
    }

    @Test
    void conditions_sortNoMustBeUnique() {
        GatewayRoute r = base();
        List<GatewayRule> cs = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1),
                GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "GET", 1));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertEquals("匹配条件第 2 条与第 1 条顺序号撞车（都是 1），同一路由内顺序号不能重复", e.getMessage());
    }

    @Test
    void actions_sortNoConflict_pointsToBothOrdinals() {
        GatewayRoute r = base();
        List<GatewayRule> as = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER, "X-A", "1", 2),
                GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER, "X-B", "2", 1),
                GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER, "X-C", "3", 2));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(List.of(), as));
        assertEquals("转发动作第 3 条与第 1 条顺序号撞车（都是 2），同一路由内顺序号不能重复", e.getMessage());
    }

    @Test
    void conditions_sortNoMustBeContiguousFromOne() {
        GatewayRoute r = base();
        List<GatewayRule> cs = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1),
                GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "GET", 3));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertEquals("匹配条件的顺序号必须从 1 起连续，缺了 2", e.getMessage());
    }

    @Test
    void actions_sortNoGap_isRejected() {
        GatewayRoute r = base();
        List<GatewayRule> as = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-A", null, 1),
                GatewayRule.create(null, RuleTypes.TYPE_RESP_ADD_HEADER, "X-B", "v", 3));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(List.of(), as));
        assertEquals("转发动作的顺序号必须从 1 起连续，缺了 2", e.getMessage());
    }

    @Test
    void condition_rejectsUnknownType() {
        GatewayRoute r = base();
        List<GatewayRule> cs = List.of(
                GatewayRule.create(null, "COOKIE", "session", "abc", 1));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertEquals("匹配条件第 1 条的类型不支持：COOKIE（只支持 PATH_PREFIX / METHOD / HEADER / QUERY）",
                e.getMessage());
    }

    @Test
    void condition_isAlwaysRequestStage() {
        GatewayRoute r = base();
        GatewayRule c = GatewayRule.create("RESPONSE", RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1);
        r.replaceRules(List.of(c), List.of());
        assertEquals(RuleTypes.STAGE_REQUEST, r.getConditions().get(0).getStage());
    }

    @Test
    void headerCondition_requiresName() {
        GatewayRoute r = base();
        List<GatewayRule> cs = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_HEADER, null, "v", 1));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertEquals("匹配条件第 1 条缺名字（头名或参数名）", e.getMessage());
    }

    @Test
    void addHeaderAction_requiresNameAndValue() {
        GatewayRoute r = base();
        List<GatewayRule> noName = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER, null, "v", 1));
        assertThrows(BizException.class, () -> r.replaceRules(List.of(), noName));

        List<GatewayRule> noValue = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER, "X-GW", null, 1));
        assertThrows(BizException.class, () -> r.replaceRules(List.of(), noValue));
    }

    @Test
    void removeHeaderAction_needsNoValue() {
        GatewayRoute r = base();
        GatewayRule a = GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-Internal", null, 1);
        r.replaceRules(List.of(), List.of(a));
        assertEquals(RuleTypes.STAGE_REQUEST, r.getActions().get(0).getStage());
        assertEquals(null, r.getActions().get(0).getValue());
    }

    @Test
    void responseAction_stageIsResponse() {
        GatewayRoute r = base();
        GatewayRule a = GatewayRule.create(null, RuleTypes.TYPE_RESP_ADD_HEADER, "X-Trace", "t1", 1);
        r.replaceRules(List.of(), List.of(a));
        assertEquals(RuleTypes.STAGE_RESPONSE, r.getActions().get(0).getStage());
    }

    @Test
    void action_stageMustMatchType() {
        GatewayRoute r = base();
        List<GatewayRule> as = List.of(
                GatewayRule.create("REQUEST", RuleTypes.TYPE_RESP_ADD_HEADER, "X-Trace", "t1", 1));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(List.of(), as));
        assertEquals("转发动作第 1 条的方向与类型对不上：RESP_ADD_HEADER 应为 RESPONSE", e.getMessage());
    }

    @Test
    void replaceRules_isWholeReplacement() {
        GatewayRoute r = base();
        r.replaceRules(
                List.of(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1)),
                List.of());
        assertEquals(1, r.getConditions().size());

        r.replaceRules(
                List.of(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/pay/", 1),
                        GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "POST", 2)),
                List.of());
        assertEquals(2, r.getConditions().size());
    }
}
