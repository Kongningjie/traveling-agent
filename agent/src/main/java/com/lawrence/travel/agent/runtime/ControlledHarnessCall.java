package com.lawrence.travel.agent.runtime;

import com.lawrence.travel.application.safety.SafetyGate;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 绕过Harness紧急默认Flush；只对明确零用量的上下文超限恢复一次，其他失败不重复请求。 */
public final class ControlledHarnessCall {
    /** 命名空间。 */
    private ControlledHarnessCall() { }
    /** @param factory 每次新建隔离实例 @param normal 正常完整消息 @param recovery 同预算紧急压缩 @param guard 返回前版本检查 @param safety 完整输出检查 @param cancelled 取消标志 @param active 实际框架句柄 @return 未流式泄露且检查通过的最终正文 */
    public static String execute(Supplier<HarnessAgent> factory,Supplier<List<Msg>> normal,Supplier<List<Msg>> recovery,Runnable guard,SafetyGate safety,BooleanSupplier cancelled,Consumer<HarnessAgent> active) {
        for(int attempt=0;attempt<2;attempt++) {
            if(cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED");
            try(var harness=factory.get()) {
                active.accept(harness); if(cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED"); var messages=(attempt==0?normal:recovery).get();
                if(cancelled.getAsBoolean()) throw new IllegalStateException("MODEL_CANCELLED");
                var result=harness.getDelegate().call(messages,RestrictedHarnessFactory.StructuredReply.class).block();
                if(result==null) throw new IllegalStateException("MODEL_OUTPUT_INVALID");
                String text=result.getStructuredData(RestrictedHarnessFactory.StructuredReply.class).answer(); safety.require("OUTPUT",text); guard.run(); return text;
            } catch(RuntimeException failure) { if(attempt!=0 || !recoverable(failure)) throw failure; }
        }
        throw new IllegalStateException("CONTEXT_RECOVERY_EXHAUSTED");
    }
    /** @param failure 内部异常链 @return 只有传输明确零用量的固定分类可恢复，不依赖任意异常字符串 */
    private static boolean recoverable(Throwable failure) {
        for(Throwable current=failure;current!=null;current=current.getCause()) if(current instanceof HttpTransportException && "MODEL_CONTEXT_LIMIT_SAFE".equals(current.getMessage())) return true;
        return false;
    }
}
