package com.apigw.domain.route;

/**
 * 子项种类、方向、类型常量（避免魔法字符串散落各处）。
 *
 * 库里的 routing 定义直接复用它，落 Redis 时也按这些字符串存。
 */
public final class RuleTypes {

    private RuleTypes() {
    }

    /** 子项种类：匹配条件。 */
    public static final String KIND_CONDITION = "CONDITION";
    /** 子项种类：转发动作。 */
    public static final String KIND_ACTION = "ACTION";

    /** 方向：请求。 */
    public static final String STAGE_REQUEST = "REQUEST";
    /** 方向：响应。 */
    public static final String STAGE_RESPONSE = "RESPONSE";

    /** 条件类型：路径前缀。 */
    public static final String TYPE_PATH_PREFIX = "PATH_PREFIX";
    /** 条件类型：请求方法。 */
    public static final String TYPE_METHOD = "METHOD";
    /** 条件类型：请求头。 */
    public static final String TYPE_HEADER = "HEADER";
    /** 条件类型：查询参数。 */
    public static final String TYPE_QUERY = "QUERY";

    /** 动作类型：给请求补头。 */
    public static final String TYPE_REQ_ADD_HEADER = "REQ_ADD_HEADER";
    /** 动作类型：给请求删头。 */
    public static final String TYPE_REQ_REMOVE_HEADER = "REQ_REMOVE_HEADER";
    /** 动作类型：给响应补头。 */
    public static final String TYPE_RESP_ADD_HEADER = "RESP_ADD_HEADER";
    /** 动作类型：给响应删头。 */
    public static final String TYPE_RESP_REMOVE_HEADER = "RESP_REMOVE_HEADER";
}
