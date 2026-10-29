package com.lawrence.travel.application.evaluation;

import java.util.Objects;
import java.util.UUID;

/** 评测发布登记用例；不执行网络请求，不自动重试UNKNOWN，不提供HTTP写入口。 */
public final class EvaluationPublicationService {
    private final EvaluationPublication.Store store;

    /**
     * 绑定数据库时钟和持久唯一键，不装配本地放行替身。
     *
     * @param store PG持久端口
     */
    public EvaluationPublicationService(EvaluationPublication.Store store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * 首次登记副本和尝试；调用者须在短事务提交后发送，禁止将网络请求放入该事务。
     *
     * @param request 可信发布请求
     * @return 首次尝试许可；相同请求重试也不重新发放
     * @throws IllegalStateException 已领取、冲突、越权或已阻断
     */
    public EvaluationPublication.Permit claim(EvaluationPublication.Request request) {
        Objects.requireNonNull(request);
        UUID attempt = UUID.randomUUID();
        var expiry = store.claim(request, attempt);
        allowed(expiry != null);
        return new EvaluationPublication.Permit(request, attempt, expiry);
    }

    /**
     * 发送前在PG中消耗一次许可；提交后才发送，崩溃后不能重新消费或扫描重放UNKNOWN。
     *
     * @param permit 首次调用保留的许可
     * @throws IllegalStateException 已撤销、过期、越权或许可不匹配
     */
    public void beginSend(EvaluationPublication.Permit permit) {
        EvaluationRetention.require(permit != null);
        allowed(store.beginSend(permit));
    }

    /**
     * 记录可信适配器的回执；被阻断时返回BLOCKED，不把迟到成功当作恢复访问。
     *
     * @param permit 原始许可
     * @param outcome ACCEPTED或CONFIRMED
     * @param evidenceHash 接收或查询证据指纹
     * @return 当前持久状态
     * @throws IllegalStateException 许可不匹配、重复或倒退回执
     */
    public EvaluationPublication.State acknowledge(
            EvaluationPublication.Permit permit,
            EvaluationPublication.State outcome,
            String evidenceHash) {
        EvaluationRetention.require(
                permit != null
                        && EvaluationRetention.hash(evidenceHash)
                        && (outcome == EvaluationPublication.State.ACCEPTED
                                || outcome == EvaluationPublication.State.CONFIRMED));
        var result = store.acknowledge(permit, outcome, evidenceHash);
        allowed(result != null);
        return result;
    }

    /**
     * 为已阻断运行保存独立标记；不清理平台副本或解除入口屏障。
     *
     * @param run 原始运行
     * @throws IllegalStateException 运行仍活跃或不存在
     */
    public void seal(UUID run) {
        EvaluationRetention.require(run != null);
        allowed(store.seal(run));
    }

    /**
     * 不区分冲突与其他账号记录，避免泄露运行存在性。
     *
     * @param result 仓储判定
     */
    private static void allowed(boolean result) {
        if (!result) throw new IllegalStateException("EVALUATION_PUBLICATION_DENIED");
    }
}
