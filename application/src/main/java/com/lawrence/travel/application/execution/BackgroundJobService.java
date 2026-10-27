package com.lawrence.travel.application.execution;

import com.lawrence.travel.application.port.BackgroundWorkSource;
import com.lawrence.travel.application.port.JobHandler;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.domain.execution.BackgroundJob;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 非聊天处理器入口；未装配解析／分析／记忆处理器时不领取相应作业。 */
public final class BackgroundJobService implements BackgroundWorkSource {
    private final JobStore store;
    private final Map<BackgroundJob.Kind, JobHandler> handlers;
    private final boolean parser;

    /**
     * @param store 持久作业
     * @param handlers 已实现处理器，类型不能重复
     * @param parser 使用解析容量
     */
    public BackgroundJobService(JobStore store, List<JobHandler> handlers, boolean parser) {
        this.store = store;
        this.parser = parser;
        this.handlers =
                handlers.stream()
                        .filter(h -> (h.kind() == BackgroundJob.Kind.DOCUMENT_PARSE) == parser)
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        JobHandler::kind, Function.identity()));
        if (this.handlers.containsKey(BackgroundJob.Kind.CHAT_INPUT))
            throw new IllegalArgumentException("CHAT_HANDLER_ALREADY_OWNED");
    }

    /** {@inheritDoc} 不把解析CPU容量算作模型容量。 */
    @Override
    public boolean parserCapacity() {
        return parser;
    }

    /** {@inheritDoc} 原领取作为唯一执行与提交身份，恢复不产生新operation。 */
    @Override
    public Optional<Runnable> claimWork() {
        return claimed(store.claim(handlers.keySet()));
    }

    /** {@inheritDoc} 已取消但仍在途的同用户请求不提前释放物理用户槽。 */
    @Override
    public Optional<Runnable> claimWorkExcluding(java.util.Set<java.util.UUID> excluded) {
        return claimed(store.claim(handlers.keySet(), excluded));
    }

    /**
     * @param selected 原持久资格
     * @return 保留归属和续租的可运行工作
     */
    private Optional<Runnable> claimed(Optional<Jobs.Claim> selected) {
        return selected.map(
                c ->
                        new LeasedWork() {
                            /** {@inheritDoc} 归属来自PG资格而不是模型输入。 */
                            @Override
                            public java.util.UUID userId() {
                                return c.fence().scope().userId();
                            }

                            /** 由既有Worker心跳复核版本和lease。 */
                            @Override
                            public boolean renew() {
                                return store.renew(c.fence());
                            }

                            /** 处理器异常只写固定分类；过期／撤销后保留原权威状态。 */
                            @Override
                            public void run() {
                                try (var ignored =
                                        ExecutionCorrelation.bind(
                                                new ExecutionCorrelation.Ids(
                                                        c.job().requestId(), c.job().traceId()))) {
                                    try {
                                        var handler = handlers.get(c.job().kind());
                                        var output = handler.execute(c);
                                        handler.commit(c, output, () -> store.finish(c, output));
                                    } catch (RuntimeException failure) {
                                        Thread.interrupted();
                                        if (store.live(c.fence()))
                                            store.finish(
                                                    c,
                                                    new Jobs.Output(
                                                            BackgroundJob.State.PAUSED,
                                                            null,
                                                            List.of(),
                                                            "JOB_PROCESSING_FAILED"));
                                    }
                                }
                            }
                        });
    }
}
