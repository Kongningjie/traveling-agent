package com.lawrence.travel.domain.execution;

import com.lawrence.travel.domain.rule.DomainViolation;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.ILLEGAL_TRANSITION;

/** 阶段执行请求状态，不是用户可见 SSE 事件，也不负责实际 SQL 领取。 */
public enum OutboxState {
    /** 可领取。 */
    READY,
    /** 已持有有效租约。 */
    LEASED,
    /** 等待重试时间。 */
    RETRY_WAIT,
    /** 原子提交完成。 */
    DONE,
    /** 代次或需求失效。 */
    OBSOLETE,
    /** 尝试耗尽或永久失败。 */
    DEAD;

    /**
     * 检查事件转换；LEASED 到 READY 仅供租约恢复事务使用，不恢复旧执行权。
     * @param next 目标状态
     * @return 合法的目标状态
     */
    public OutboxState transitionTo(OutboxState next) {
        boolean allowed =
                switch (this) {
                    case READY -> next == LEASED || next == OBSOLETE;
                    case LEASED ->
                            next == DONE
                                    || next == RETRY_WAIT
                                    || next == READY
                                    || next == OBSOLETE
                                    || next == DEAD;
                    case RETRY_WAIT -> next == LEASED || next == OBSOLETE || next == DEAD;
                    case DONE, OBSOLETE, DEAD -> false;
                };
        DomainViolation.require(allowed, ILLEGAL_TRANSITION);
        return next;
    }
}
