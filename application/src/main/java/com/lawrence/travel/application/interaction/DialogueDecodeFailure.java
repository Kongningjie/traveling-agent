package com.lawrence.travel.application.interaction;

/** 业务解码的脱敏诊断；分类及路径只来自枚举，不保留解析器、原始路径、值或异常链。 */
public final class DialogueDecodeFailure extends RuntimeException {
    /** 解码错误的固定类别，不根据异常message匹配。 */
    public enum Kind {
        /** JSON语法、重复键等流读取问题。 */ SYNTAX,
        /** 对象、数组、标量或空根结构不符合契约。 */ SHAPE,
        /** 枚举值无效。 */ ENUM,
        /** UUID转换失败。 */ UUID,
        /** 领域构造器拒绝；路径只定位到对象，不能猜具体不变量。 */ CONSTRAINT,
        /** 无法可靠分类，不输出原始异常。 */ UNKNOWN
    }
    /** 允许记录的固定字段路径；数组下标统一省略，未知属性名不进入诊断。 */
    public enum Field {
        /** 根对象或不可定位路径。 */ ROOT("$"), /** 诉求数组/对象。 */ INTENTS("intents[]"),
        /** 一级分类。 */ INTENT_KIND("intents[].kind"), /** 动作。 */ INTENT_ACTION("intents[].action"),
        /** 意图依据。 */ INTENT_QUOTE("intents[].quote"), /** 事实数组/对象。 */ FACTS("facts[]"),
        /** 事实字段名。 */ FACT_FIELD("facts[].field"), /** 事实值。 */ FACT_VALUE("facts[].value"),
        /** 事实依据。 */ FACT_QUOTE("facts[].quote"), /** 软偏好操作。 */ FACT_OPERATION("facts[].operation"),
        /** 回复。 */ REPLY("reply"), /** 澄清。 */ CLARIFICATION("clarification"),
        /** 目标身份。 */ TARGET_ID("targetId"), /** 偏好类别。 */ CATEGORY("category"),
        /** 偏好值。 */ VALUE("value"), /** 城市。 */ CITY("city"),
        /** 已解决输入身份。 */ RESOLVED_INPUTS("resolvedInputs[]"), /** 文件建议。 */ ADVICE("advice[]");
        private final String path;
        /** @param path 编译期固定路径，不能由模型提供 */
        Field(String path) { this.path = path; }
        /** @return 不含模型值、动态键名或数组下标的固定路径 */
        public String path() { return path; }
    }
    private final Kind kind;
    private final Field field;
    /** @param kind 固定类别 @param field 白名单路径；禁止原始异常cause及suppressed */
    public DialogueDecodeFailure(Kind kind, Field field) {
        super("DIALOGUE_DECODE_" + java.util.Objects.requireNonNull(kind).name() + "_" + java.util.Objects.requireNonNull(field).name(), null, false, true);
        this.kind = kind; this.field = field;
    }
    /** @return 固定错误类别 */
    public Kind kind() { return kind; }
    /** @return 白名单字段身份 */
    public Field field() { return field; }
    /** @return 仅由枚举构成、可写入既有48字符error列的固定码 */
    public String diagnosticCode() { return "DIALOGUE_DECODE_" + kind.name() + "_" + field.name(); }
}
