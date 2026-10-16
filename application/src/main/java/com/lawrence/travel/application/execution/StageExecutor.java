package com.lawrence.travel.application.execution;

import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.StageHandler;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import com.lawrence.travel.domain.rule.DomainViolation;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import java.util.List;

/**
 * 唯一阶段执行器；业务处理在数据库事务外运行，异常只转换为固定分类。
 */
public final class StageExecutor {
    /** 框架处理器中断窗口；数据库阶段不能使用线程中断取消。 */
    public interface Control {
        /** @return 是否允许进入可中断业务阶段，原子关闭与取消登记竞争窗口 */
        boolean enter();
        /** 退出可中断区域，并清除处理器遗留中断，避免 JDBC 因标志关闭连接。 */
        void leave();
        /** @return 是否已请求停止，停止后不再保存新的阶段结果 */
        boolean cancelled();
    }
    private final ExecutionEngineStore store;
    private final List<StageHandler> handlers;
    /**
     * @param store 权威状态
     * @param handlers 明确注册的处理器；不得默认替换为真实模型
     */
    public StageExecutor(ExecutionEngineStore store, List<StageHandler> handlers) { this.store = store; this.handlers = List.copyOf(handlers); }
    /**
     * @param type 阶段
     * @return 恰好一个处理器支持；未配置或冲突均不领取
     */
    public boolean supports(StageType type) { return handlers.stream().filter(h -> h.supports(type)).count() == 1; }
    /**
     * @param claim 已领取执行；中断后只尽力停止，业务资格以数据库判断
     * @param control 仅框架／业务处理阶段可中断，数据库操作始终由事务和 fence 判定
     */
    public void execute(ExecutionEngineStore.Claim claim, Control control) {
        try {
            if (control.cancelled()) return;
            if (!store.renew(claim.fence())) return;
            var matches = handlers.stream().filter(h -> h.supports(claim.step().key().type())).toList();
            if (matches.size() != 1) { store.fail(claim.fence(), ExecutionEngineStore.Failure.PERMANENT); return; }
            if (!control.enter()) return;
            StageOutput output;
            try { output = matches.getFirst().execute(claim); }
            finally { control.leave(); }
            if (control.cancelled()) return;
            store.commit(store.prepare(claim, output));
        } catch (CallRejectedException rejected) {
            if (control.cancelled()) return;
            var failure = switch (rejected.reason()) {
                case CALL_BUDGET_EXHAUSTED, ACTIVE_TIME_EXHAUSTED -> ExecutionEngineStore.Failure.BUDGET;
                case MODEL_CONCURRENCY_FULL -> ExecutionEngineStore.Failure.RETRYABLE;
                default -> ExecutionEngineStore.Failure.QUOTA;
            };
            store.fail(claim.fence(), failure);
        } catch (TravelStorageFailure rejected) {
            if (control.cancelled()) return;
            store.fail(claim.fence(), rejected.code() == TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE
                    ? ExecutionEngineStore.Failure.RETRYABLE : ExecutionEngineStore.Failure.PERMANENT);
        } catch (DomainViolation | IllegalArgumentException invalid) {
            if (control.cancelled()) return;
            store.fail(claim.fence(), ExecutionEngineStore.Failure.PERMANENT);
        } catch (Exception failure) {
            if (control.cancelled()) return;
            // 不输出异常内容，处理器可能持有提示词、供应商响应或认证信息。
            store.fail(claim.fence(), ExecutionEngineStore.Failure.RETRYABLE);
        }
    }
}
