package com.lawrence.travel.application.itinerary;

import com.lawrence.travel.application.execution.AgentCallFailure;

/** 规划产物解码失败，只传递固定分类与声明字段位置。 */
public final class PlanningDecodeFailure extends AgentCallFailure {
    private final String field;
    private final String category;

    /**
     * 创建不含模型值的失败。
     *
     * @param field 从 Java 声明属性取得的字段路径
     * @param category 固定解码分类
     */
    public PlanningDecodeFailure(String field, String category) {
        super(Code.MODEL_OUTPUT_SCHEMA);
        this.field = field;
        this.category = category;
    }

    /**
     * 读取解码位置。
     *
     * @return 只包含声明属性和数组占位符
     */
    public String field() {
        return field;
    }

    /**
     * 读取失败类别。
     *
     * @return 固定分类
     */
    public String category() {
        return category;
    }
}
