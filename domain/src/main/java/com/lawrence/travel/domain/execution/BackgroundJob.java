package com.lawrence.travel.domain.execution;

import java.util.Set;

/** 独立聊天及附件作业规则；没有旅行任务时仍有持久化身份，不代替旅行阶段状态机。 */
public final class BackgroundJob {
    /** 规则命名空间，不允许实例化。 */
    private BackgroundJob() { }
    /** 持久化处理类型；解析／分析／维护处理器在后续阶段接入。 */
    public enum Kind {
        /** 自由输入理解。 */ CHAT_INPUT,
        /** 隔离文档解析。 */ DOCUMENT_PARSE,
        /** 基于已就绪资料分析。 */ DOCUMENT_ANALYZE,
        /** 有预算归属的工作记忆维护。 */ MEMORY_UPDATE
    }
    /** 作业状态与用户消息状态分开，终态不能被隐式恢复。 */
    public enum State {
        /** 等待调度。 */ QUEUED,
        /** 有有效租约。 */ RUNNING,
        /** 等待资料或其他作业。 */ WAITING_DEPENDENCY,
        /** 等待用户。 */ WAITING_USER,
        /** 预算／依赖故障暂停。 */ PAUSED,
        /** 产物已提交。 */ COMPLETED,
        /** 不可继续的失败。 */ FAILED,
        /** 用户取消。 */ CANCELLED,
        /** 已被新版本取代。 */ SUPERSEDED
    }
    /** @param state 当前状态 @return 是否为不可复活终态 */
    public static boolean terminal(State state) {
        return Set.of(State.COMPLETED, State.FAILED, State.CANCELLED, State.SUPERSEDED).contains(state);
    }
    /** @param before 当前状态 @param after 目标状态 @return 是否允许该转换；重试只允许暂停或用户等待，终态不复活 */
    public static boolean allows(State before, State after) {
        if (before == after) return true;
        return switch (before) {
            case QUEUED -> Set.of(State.RUNNING, State.COMPLETED, State.CANCELLED, State.SUPERSEDED, State.PAUSED).contains(after);
            case RUNNING -> after != State.RUNNING;
            case WAITING_DEPENDENCY -> Set.of(State.QUEUED, State.CANCELLED, State.SUPERSEDED, State.PAUSED).contains(after);
            case WAITING_USER -> Set.of(State.QUEUED, State.COMPLETED, State.CANCELLED, State.SUPERSEDED).contains(after);
            case PAUSED -> Set.of(State.QUEUED, State.CANCELLED, State.SUPERSEDED).contains(after);
            default -> false;
        };
    }
}
