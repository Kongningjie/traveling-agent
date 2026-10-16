package com.lawrence.travel.domain.travel;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.PENDING_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_REVISION;

/**
 * 任务不可变状态；执行代次先于需求提取提升，旧行程指针始终保留。
 * @param scope 用户与任务归属
 * @param constraintRevision 当前需求版本，从 1 开始
 * @param executionGeneration 当前执行代次，从 1 开始
 * @param currentVersionId 已接受快照指针，初始可空
 * @param pendingInputs 尚未解释或未完成提取的输入，按输入 ID 跟踪
 */
public record TravelTask(TaskScope scope, long constraintRevision, long executionGeneration,
                         UUID currentVersionId, Map<UUID, PendingInput> pendingInputs) {
    /** 区分待识别消息与已确认的修改，避免同一修改重复提升代次。 */
    public enum InputState {
        /** 消息尚待识别，阻止接受但暂不推断为修改。 */ UNCLASSIFIED,
        /** 已确认修改，旧执行已失效，需求尚待可靠提取。 */ CHANGE_REGISTERED
    }

    /**
     * 待处理输入的顺序与分类，序号由任务消息序列提供。
     * @param sequence 任务内正序号，不以 Map 遍历顺序推断先后
     * @param state 当前分类
     */
    public record PendingInput(long sequence, InputState state) {
        /** 拒绝缺失分类与非正顺序。 */
        public PendingInput { DomainViolation.require(sequence > 0 && state != null, INVALID_INPUT); }
    }

    /** 校验版本并防御性复制，避免外部集合改变任务状态。 */
    public TravelTask {
        DomainViolation.require(scope != null && constraintRevision > 0 && executionGeneration > 0 && pendingInputs != null, INVALID_INPUT);
        DomainViolation.require(pendingInputs.entrySet().stream().allMatch(entry -> entry.getKey() != null && entry.getValue() != null), INVALID_INPUT);
        pendingInputs = Map.copyOf(pendingInputs);
        DomainViolation.require(pendingInputs.values().stream().map(PendingInput::sequence).distinct().count() == pendingInputs.size(), INVALID_INPUT);
    }

    /**
     * 登记待识别输入；重复同一 ID 幂等，调用者另行校验同键请求内容。
     * @param inputId 服务端已可靠登记的消息 ID
     * @param sequence 同任务消息序号，不能把新的 ID 插到已有序号
     * @return 保留全部未完成输入的新状态
     */
    public TravelTask registerInput(UUID inputId, long sequence) {
        DomainViolation.require(inputId != null && sequence > 0, INVALID_INPUT);
        if (pendingInputs.containsKey(inputId)) {
            DomainViolation.require(pendingInputs.get(inputId).sequence() == sequence,
                    com.lawrence.travel.domain.rule.DomainViolation.Code.IDEMPOTENCY_CONFLICT);
            return this;
        }
        DomainViolation.require(pendingInputs.values().stream().noneMatch(input -> input.sequence() == sequence), INVALID_INPUT);
        var next = new HashMap<>(pendingInputs);
        next.put(inputId, new PendingInput(sequence, InputState.UNCLASSIFIED));
        return new TravelTask(scope, constraintRevision, executionGeneration, currentVersionId, next);
    }

    /**
     * 确认某输入属于修改时立即撤销旧执行资格，尚不改变已确认需求。
     * @param inputId 已登记的输入
     * @return 执行代次提升一次的新状态
     */
    public TravelTask registerChange(UUID inputId) {
        DomainViolation.require(inputId != null, INVALID_INPUT);
        DomainViolation.require(pendingInputs.containsKey(inputId), ILLEGAL_TRANSITION);
        if (pendingInputs.get(inputId).state() == InputState.CHANGE_REGISTERED) return this;
        var next = new HashMap<>(pendingInputs);
        next.put(inputId, new PendingInput(pendingInputs.get(inputId).sequence(), InputState.CHANGE_REGISTERED));
        return new TravelTask(scope, constraintRevision, Math.incrementExact(executionGeneration), currentVersionId, next);
    }

    /**
     * 成功保存结构化修改后推进需求版本；失败提取时不得调用或移除输入。
     * @param inputId 已确认修改的输入
     * @param expectedRevision 读取时的需求版本
     * @return 仅移除该输入、保留其他未完成要求的新状态
     */
    public TravelTask applyChange(UUID inputId, long expectedRevision) {
        DomainViolation.require(constraintRevision == expectedRevision, STALE_REVISION);
        requireNextInput(inputId);
        DomainViolation.require(pendingInputs.get(inputId).state() == InputState.CHANGE_REGISTERED, ILLEGAL_TRANSITION);
        var next = new HashMap<>(pendingInputs);
        next.remove(inputId);
        return new TravelTask(scope, Math.incrementExact(constraintRevision), executionGeneration, currentVersionId, next);
    }

    /**
     * 已确认纯咨询时清除该阻断，不能借此丢弃已登记修改。
     * @param inputId 已识别为非修改的消息
     * @return 不改变需求和代次的新状态
     */
    public TravelTask resolveInquiry(UUID inputId) {
        requireNextInput(inputId);
        DomainViolation.require(pendingInputs.get(inputId).state() == InputState.UNCLASSIFIED, ILLEGAL_TRANSITION);
        var next = new HashMap<>(pendingInputs);
        next.remove(inputId);
        return new TravelTask(scope, constraintRevision, executionGeneration, currentVersionId, next);
    }

    /**
     * 取消或显式新运行时提升代次；保留需求、生效行程和未完成输入。
     * @return 旧执行不能再提交的新状态
     */
    public TravelTask fenceExecution() {
        return new TravelTask(scope, constraintRevision, Math.incrementExact(executionGeneration), currentVersionId, pendingInputs);
    }

    /** 接受路径使用的独立门槛；不得将该检查用于阻止 UNDERSTAND 解释输入。 */
    public void requireNoPendingInputs() { DomainViolation.require(pendingInputs.isEmpty(), PENDING_INPUT); }
    /**
     * 解释输入按消息顺序进行，避免后一句覆盖尚未提取的前一句有效要求。
     * @param inputId 当前要完成处理的输入
     */
    private void requireNextInput(UUID inputId) {
        DomainViolation.require(inputId != null, INVALID_INPUT);
        PendingInput input = pendingInputs.get(inputId);
        DomainViolation.require(input != null && input.sequence() == pendingInputs.values().stream()
                .mapToLong(PendingInput::sequence).min().orElse(Long.MAX_VALUE), ILLEGAL_TRANSITION);
    }

}
