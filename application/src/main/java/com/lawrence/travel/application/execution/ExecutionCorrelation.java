package com.lawrence.travel.application.execution;

import java.util.UUID;

/**
 * 执行链路的线程局部关联范围，连接HTTP请求、后台作业、Agent角色和逐请求计量节点。
 *
 * <p>RequestCorrelationFilter建立请求身份，应用登记作业时持久化因果关联；Worker和模型虚拟线程
 * 通过bind/bindSpan/bindTrace显式恢复。Scope关闭恢复上层绑定，防止线程复用串联其他用户。
 * 本类只传递非敏感诊断身份，不是认证、租约、预算或业务提交权威，不能替代PG围栏。
 */
public final class ExecutionCorrelation {
    private static final ThreadLocal<Ids> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<UUID> SPAN = new ThreadLocal<>();
    private static final ThreadLocal<UUID> TRACE = new ThreadLocal<>();

    /**
     * @return 可嵌套诊断父级；与原业务attempt分离，避免子轨迹被当作PG阶段身份
     */
    public static UUID traceParent() {
        return TRACE.get() == null ? SPAN.get() : TRACE.get();
    }

    /**
     * @param id 子轨迹
     * @return 关闭后恢复，不改变parentSpan()业务身份
     */
    public static TraceScope bindTrace(UUID id) {
        var previous = TRACE.get();
        TRACE.set(id);
        return new TraceScope(previous);
    }

    /** 诊断轨迹线程作用域，只能在创建线程退出。 */
    public static final class TraceScope implements AutoCloseable {
        private final UUID previous;

        /**
         * @param previous 上层轨迹，可空
         */
        private TraceScope(UUID previous) {
            this.previous = previous;
        }

        /** 恢复诊断父级，不更改业务领取身份。 */
        @Override
        public void close() {
            if (previous == null) TRACE.remove();
            else TRACE.set(previous);
        }
    }

    /**
     * @return 当前阶段父span；并行调查线程必须各自绑定，不猜测最近执行阶段
     */
    public static UUID parentSpan() {
        return SPAN.get();
    }

    /**
     * @param id 当前实际阶段尝试
     * @return 用完恢复原值
     */
    public static AutoCloseableSpan bindSpan(UUID id) {
        UUID previous = SPAN.get();
        SPAN.set(id);
        return new AutoCloseableSpan(previous);
    }

    /** 跨嵌套调用恢复父span，不依赖可复用线程的隐式继承。 */
    public static final class AutoCloseableSpan implements AutoCloseable {
        private final UUID previous;

        /**
         * @param previous 外层span，可空
         */
        private AutoCloseableSpan(UUID previous) {
            this.previous = previous;
        }

        /** 清理当前线程的尝试关联。 */
        @Override
        public void close() {
            if (previous == null) SPAN.remove();
            else SPAN.set(previous);
        }
    }

    /** 工具命名空间。 */
    private ExecutionCorrelation() {}

    /**
     * @param requestId 当前请求
     * @param traceId 因果链路
     */
    public record Ids(UUID requestId, UUID traceId) {}

    /**
     * @return 已绑定ID，非HTTP入口生成本地关联，不读取认证信息
     */
    public static Ids current() {
        var ids = CURRENT.get();
        return ids == null ? new Ids(UUID.randomUUID(), UUID.randomUUID()) : ids;
    }

    /**
     * @param ids 服务端可信关联
     * @return 用完必须关闭的作用域
     */
    public static Scope bind(Ids ids) {
        var previous = CURRENT.get();
        CURRENT.set(ids);
        return new Scope(previous);
    }

    /** 嵌套作用域退出恢复旧值，防止线程复用串用户。 */
    public static final class Scope implements AutoCloseable {
        private final Ids previous;

        /**
         * @param previous 外层值，可空
         */
        private Scope(Ids previous) {
            this.previous = previous;
        }

        /** 恢复外层关联，不保留会话、密钥或用户正文。 */
        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
