package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * 路由子项：一条匹配条件，或一条转发动作。
 *
 * 类型与方向的合法组合、各类型的必填项，都由 {@link #validateAs} 统一守住；
 * 顺序号只校验底线（>=1），「从 1 起连续不重」由所属聚合 {@link GatewayRoute} 统一校验。
 */
@Getter
@Setter
public class GatewayRule {

    private String id;

    /** CONDITION / ACTION。 */
    private String ruleKind;

    /** REQUEST / RESPONSE；条件恒为 REQUEST。 */
    private String stage;

    /** 具体类型，取值见 {@link RuleTypes}。 */
    private String type;

    /** 头名 / 参数名；PATH_PREFIX 与 METHOD 不填。 */
    private String name;

    /** 匹配值或补头内容；删头动作不填。 */
    private String value;

    /** 同一路由内顺序号，从 1 起、连续、不重。 */
    private Integer sortNo;

    private static final Set<String> CONDITION_TYPES = Set.of(
            RuleTypes.TYPE_PATH_PREFIX,
            RuleTypes.TYPE_METHOD,
            RuleTypes.TYPE_HEADER,
            RuleTypes.TYPE_QUERY);

    private static final Set<String> ACTION_TYPES = Set.of(
            RuleTypes.TYPE_REQ_ADD_HEADER,
            RuleTypes.TYPE_REQ_REMOVE_HEADER,
            RuleTypes.TYPE_RESP_ADD_HEADER,
            RuleTypes.TYPE_RESP_REMOVE_HEADER);

    public static GatewayRule create(String stage, String type, String name, String value, Integer sortNo) {
        GatewayRule rule = new GatewayRule();
        rule.setStage(stage);
        rule.setType(type);
        rule.setName(name);
        rule.setValue(value);
        rule.setSortNo(sortNo);
        return rule;
    }

    /**
     * 按所属 kind 完整校验一条子项。ordinal 是它在同 kind 列表里的位次（从 1 起），
     * 出错时带上它，前端能直接指出是哪一行写错了。
     */
    public void validateAs(String expectedKind, int ordinal) {
        if (sortNo == null || sortNo < 1) {
            throw new BizException("第 " + ordinal + " 条子项的顺序号必须从 1 开始");
        }
        if (type == null || type.isBlank()) {
            throw new BizException("第 " + ordinal + " 条子项没填类型");
        }
        this.type = type.trim();

        if (RuleTypes.KIND_CONDITION.equals(expectedKind)) {
            if (!CONDITION_TYPES.contains(this.type)) {
                throw new BizException("第 " + ordinal + " 条匹配条件的类型不支持：" + this.type);
            }
            // 条件恒作用于请求方向，不接受调用方传 RESPONSE
            this.stage = RuleTypes.STAGE_REQUEST;
            validateFields(ordinal, "匹配条件");
            return;
        }

        if (!ACTION_TYPES.contains(this.type)) {
            throw new BizException("第 " + ordinal + " 条转发动作的类型不支持：" + this.type);
        }
        // 动作方向必须与类型自洽，避免 RESP_ADD_HEADER 被标成 REQUEST
        String expectStage = this.type.startsWith("RESP_")
                ? RuleTypes.STAGE_RESPONSE
                : RuleTypes.STAGE_REQUEST;
        if (stage != null && !stage.isBlank() && !expectStage.equals(stage.trim())) {
            throw new BizException("第 " + ordinal + " 条动作的方向与类型对不上：" + this.type + " 应为 " + expectStage);
        }
        this.stage = expectStage;
        validateFields(ordinal, "转发动作");
    }

    /** 按具体类型校验 name / value 的必填要求。 */
    private void validateFields(int ordinal, String label) {
        boolean nameRequired = !RuleTypes.TYPE_PATH_PREFIX.equals(this.type)
                && !RuleTypes.TYPE_METHOD.equals(this.type);
        if (nameRequired) {
            if (name == null || name.isBlank()) {
                throw new BizException("第 " + ordinal + " 条" + label + " 缺名字（头名或参数名）");
            }
            this.name = name.trim();
        } else {
            this.name = null;
        }

        boolean addHeader = RuleTypes.TYPE_REQ_ADD_HEADER.equals(this.type)
                || RuleTypes.TYPE_RESP_ADD_HEADER.equals(this.type);
        boolean valueRequired = addHeader
                || RuleTypes.TYPE_PATH_PREFIX.equals(this.type)
                || RuleTypes.TYPE_METHOD.equals(this.type)
                || RuleTypes.TYPE_HEADER.equals(this.type)
                || RuleTypes.TYPE_QUERY.equals(this.type);
        if (valueRequired) {
            if (value == null || value.isBlank()) {
                throw new BizException("第 " + ordinal + " 条" + label + " 缺取值");
            }
            this.value = value.trim();
        } else {
            // 删头动作不需要取值
            this.value = null;
        }
    }
}
