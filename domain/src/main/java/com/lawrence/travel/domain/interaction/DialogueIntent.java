package com.lawrence.travel.domain.interaction;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天模型产物的纯领域协议：粗粒度意图、受限动作、带原文依据的字段与建议。
 *
 * <p>DialogueIntentInterpreter返回JSON → TravelJson严格解码 → Result/Fact/Intent构造校验
 * → DialogueService.process调用validateSources → route决定真实业务。领域类型不访问框架或数据库。
 * 分类只描述用户诉求，不授予接受、删除、授权或解除硬约束的权限；原文引用必须连续存在，
 * 软偏好ADD/REPLACE还须经RequirementFields和应用确认边界处理。
 */
public final class DialogueIntent {
    /** 聊天和协商原文统一上限，按Java字符串长度计；不代表模型Token窗口。 */
    public static final int MAX_TEXT_LENGTH = 4000;

    /** 纯协议命名空间，不允许实例化。 */
    private DialogueIntent() {}

    /** 四类粗粒度分流；字段名称和问题回答关系不是新的一级意图。 */
    public enum Kind {
        /** 只读交流。 */
        CONVERSATION,
        /** 规划协作。 */
        PLANNING,
        /** 显式操作请求。 */
        OPERATION,
        /** 需澄清。 */
        UNCLEAR
    }

    /** 有界内部动作；危险动作必须进入应用确认流程。 */
    public enum Action {
        /** 闲聊。 */
        CHAT,
        /** 旅行问答。 */
        TRAVEL_QA,
        /** 解释。 */
        EXPLAIN_PLAN,
        /** 比较。 */
        COMPARE_OPTIONS,
        /** 检查原计划。 */
        CHECK_PLAN,
        /** 提出优化建议。 */
        OPTIMIZE_PLAN,
        /** 开始规划。 */
        START_PLAN,
        /** 补充。 */
        SUPPLEMENT,
        /** 变更需求。 */
        CHANGE_REQUIREMENTS,
        /** 调整行程。 */
        REVISE_ITINERARY,
        /** 停止。 */
        STOP,
        /** 继续。 */
        CONTINUE,
        /** 接受。 */
        ACCEPT,
        /** 拒绝。 */
        REJECT,
        /** 未决定。 */
        UNDECIDED,
        /** 授权。 */
        AUTHORIZE,
        /** 恢复版本。 */
        RESTORE_VERSION,
        /** 保存偏好。 */
        SAVE_PREFERENCE,
        /** 删除偏好。 */
        DELETE_PREFERENCE,
        /** 澄清。 */
        CLARIFY
    }

    /**
     * @param kind 一级分类
     * @param action 内部动作
     * @param quote 用户原文的连续依据
     */
    public record Intent(Kind kind, Action action, String quote) {
        /** 拒绝缺项及不相容动作，避免咨询信封夹带执行操作。 */
        public Intent {
            if (kind == null
                    || action == null
                    || quote == null
                    || quote.isBlank()
                    || quote.length() > MAX_TEXT_LENGTH) invalid();
            boolean valid =
                    switch (kind) {
                        case CONVERSATION ->
                                Set.of(
                                                Action.CHAT,
                                                Action.TRAVEL_QA,
                                                Action.EXPLAIN_PLAN,
                                                Action.COMPARE_OPTIONS,
                                                Action.CHECK_PLAN,
                                                Action.OPTIMIZE_PLAN)
                                        .contains(action);
                        case PLANNING ->
                                Set.of(
                                                Action.START_PLAN,
                                                Action.SUPPLEMENT,
                                                Action.CHANGE_REQUIREMENTS,
                                                Action.REVISE_ITINERARY)
                                        .contains(action);
                        case OPERATION ->
                                Set.of(
                                                Action.STOP,
                                                Action.CONTINUE,
                                                Action.ACCEPT,
                                                Action.REJECT,
                                                Action.UNDECIDED,
                                                Action.AUTHORIZE,
                                                Action.RESTORE_VERSION,
                                                Action.SAVE_PREFERENCE,
                                                Action.DELETE_PREFERENCE)
                                        .contains(action);
                        case UNCLEAR -> action == Action.CLARIFY;
                    };
            if (!valid) invalid();
        }
    }

    /**
     * @param field 旅行规范字段
     * @param value 规范值
     * @param quote 原文依据
     * @param operation 软偏好追加或明确替换，缺省追加
     */
    public record Fact(
            String field,
            String value,
            String quote,
            RequirementFields.PreferenceOperation operation) {
        /**
         * @param field 字段
         * @param value 规范值
         * @param quote 原文；兼容旧协议，软偏好默认追加
         */
        public Fact(String field, String value, String quote) {
            this(field, value, quote, RequirementFields.PreferenceOperation.ADD);
        }

        /** 有界字段，详细旅行规则仍由 RequirementFields 校验。 */
        public Fact {
            operation = operation == null ? RequirementFields.PreferenceOperation.ADD : operation;
            if (field == null
                    || value == null
                    || value.isBlank()
                    || value.length() > 500
                    || quote == null
                    || quote.isBlank()
                    || operation == RequirementFields.PreferenceOperation.REPLACE
                            && !RequirementFields.isPreference(field)) invalid();
        }
    }

    /**
     * @param intents 有序诉求
     * @param facts 需求增量
     * @param reply 普通回复
     * @param clarification 待澄清原因
     * @param targetId 已知操作目标，可空
     * @param category 偏好类别
     * @param value 偏好原文内容
     * @param city 偏好适用城市
     * @param resolvedInputs 本轮明确澄清的旧输入，仅可引用服务端提供的待澄清身份
     * @param advice 只读文件分析建议，可空表示无分析
     */
    public record Result(
            List<Intent> intents,
            List<Fact> facts,
            String reply,
            String clarification,
            UUID targetId,
            String category,
            String value,
            String city,
            List<UUID> resolvedInputs,
            List<PlanAdvice.Draft> advice) {
        /**
         * @param intents 诉求
         * @param facts 增量
         * @param reply 回复
         * @param clarification 歧义
         * @param targetId 目标
         * @param category 类别
         * @param value 内容
         * @param city 范围
         * @param resolvedInputs 旧问题；兼容无分析结果的调用
         */
        public Result(
                List<Intent> intents,
                List<Fact> facts,
                String reply,
                String clarification,
                UUID targetId,
                String category,
                String value,
                String city,
                List<UUID> resolvedInputs) {
            this(
                    intents,
                    facts,
                    reply,
                    clarification,
                    targetId,
                    category,
                    value,
                    city,
                    resolvedInputs,
                    List.of());
        }

        /**
         * @param intents 诉求
         * @param facts 增量
         * @param reply 回复
         * @param clarification 歧义
         * @param targetId 目标
         * @param category 偏好类型
         * @param value 内容
         * @param city 范围；无历史澄清时使用空集合
         */
        public Result(
                List<Intent> intents,
                List<Fact> facts,
                String reply,
                String clarification,
                UUID targetId,
                String category,
                String value,
                String city) {
            this(intents, facts, reply, clarification, targetId, category, value, city, List.of());
        }

        /** 咨询不能携带需求增量，多操作或操作与修改混合时由应用要求拆清范围。 */
        public Result {
            if (intents == null
                    || intents.isEmpty()
                    || intents.size() > 4
                    || facts == null
                    || facts.size() > 20
                    || reply == null
                    || reply.length() > 6000
                    || clarification == null
                    || clarification.length() > 1000
                    || category == null
                    || category.length() > 40
                    || value == null
                    || value.length() > 500
                    || city == null
                    || city.length() > 80) invalid();
            intents = List.copyOf(intents);
            facts = List.copyOf(facts);
            resolvedInputs = resolvedInputs == null ? List.of() : List.copyOf(resolvedInputs);
            if (resolvedInputs.size() > 5
                    || Set.copyOf(resolvedInputs).size() != resolvedInputs.size()) invalid();
            advice = advice == null ? List.of() : List.copyOf(advice);
            if (advice.size() > 8
                    || !advice.isEmpty()
                            && intents.stream().anyMatch(i -> i.kind() != Kind.CONVERSATION))
                invalid();
            if (!facts.isEmpty() && intents.stream().noneMatch(i -> i.kind() == Kind.PLANNING))
                invalid();
        }

        /**
         * @param text 原始消息，模型不能以历史或摘要中的片段伪造本轮用户授权
         */
        public void validateSource(String text) {
            validateSources(text, List.of());
        }

        /**
         * @param text 本轮原文
         * @param pending 服务端确认尚未解决的原文；只允许继承事实，不能从历史推断本轮操作或偏好授权
         */
        public void validateSources(String text, List<String> pending) {
            if (text == null
                    || pending == null
                    || intents.stream().anyMatch(i -> !text.contains(i.quote()))
                    || facts.stream()
                            .anyMatch(
                                    f ->
                                            !text.contains(f.quote())
                                                    && pending.stream()
                                                            .noneMatch(p -> p.contains(f.quote()))))
                invalid();
            if (!value.isBlank() && !text.contains(value)) invalid();
        }
    }

    /** 固定错误不回显模型正文或用户数据。 */
    private static void invalid() {
        throw new IllegalArgumentException("DIALOGUE_INTERPRETATION_INVALID");
    }
}
