package com.lawrence.travel.infrastructure.evaluation;

import com.lawrence.travel.application.evaluation.EvaluationScoring;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.application.travel.TravelStorageFailure.Code;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/** 评分PG适配器；加入调用者短事务，不启动网络请求或平台采集。 */
public final class PostgresEvaluationScoringStore implements EvaluationScoring.Store {
    private final EvaluationScoringMapper mapper;
    private final TravelJson json;

    /**
     * 绑定评分SQL和固定JSON协议。
     *
     * @param mapper 评分SQL
     * @param json 固定编解码器
     */
    public PostgresEvaluationScoringStore(EvaluationScoringMapper mapper, TravelJson json) {
        this.mapper = Objects.requireNonNull(mapper);
        this.json = Objects.requireNonNull(json);
    }

    /** {@inheritDoc} 同run不同来源拒绝；没有活跃run也不能悬空登记。 */
    @Override
    public boolean record(UUID owner, EvaluationScoring.Source source, String fingerprint) {
        return database(
                () -> {
                    requireTransaction();
                    if (mapper.lockRun(owner, source.context().itemRunId()) == null) return false;
                    return mapper.record(
                                    owner,
                                    source.context().itemRunId(),
                                    json.encode(source),
                                    fingerprint)
                            == 1;
                });
    }

    /** {@inheritDoc} 写入锁顺序固定run→来源；到期在最终查询再次过滤。 */
    @Override
    public EvaluationScoring.Stored read(UUID owner, UUID run, boolean lock) {
        return database(
                () -> {
                    if (lock) requireTransaction();
                    if (lock
                            && (mapper.lockRun(owner, run) == null
                                    || mapper.lockSource(owner, run) == null)) return null;
                    var row = mapper.read(owner, run);
                    if (row == null) return null;
                    var source = decode(row.source(), EvaluationScoring.Source.class);
                    var receipt =
                            row.receipt() == null
                                    ? null
                                    : decode(row.receipt(), EvaluationScoring.Receipt.class);
                    return new EvaluationScoring.Stored(
                            source, row.fingerprint(), row.revision(), receipt);
                });
    }

    /** {@inheritDoc} 同账号同请求不同内容明确冲突，不重新计算新回执。 */
    @Override
    public EvaluationScoring.Receipt receipt(UUID owner, UUID requestId, String requestHash) {
        return database(
                () -> {
                    var saved = mapper.receipt(owner, requestId);
                    if (saved == null) return null;
                    if (!saved.requestHash().equals(requestHash))
                        throw new TravelStorageFailure(Code.IDEMPOTENCY_CONFLICT);
                    return decode(saved.receipt(), EvaluationScoring.Receipt.class);
                });
    }

    /** {@inheritDoc} 追加和指针更新在同一调用事务；后一步失败抛错使整个追加回滚。 */
    @Override
    public boolean append(
            UUID owner,
            long expectedRevision,
            String requestHash,
            EvaluationScoring.Receipt receipt) {
        return database(
                () -> {
                    requireTransaction();
                    if (mapper.insert(
                                    owner,
                                    receipt.runId(),
                                    expectedRevision,
                                    receipt.requestId(),
                                    requestHash,
                                    json.encode(receipt))
                            != 1) return false;
                    if (mapper.advance(
                                    owner, receipt.runId(), expectedRevision, receipt.requestId())
                            != 1) throw new TravelStorageFailure(Code.STALE_VERSION);
                    return true;
                });
    }

    /**
     * 存储损坏不回显JSON，不把不完整记录作为有效评分返回。
     *
     * @param value 持久JSON
     * @param type 固定目标类型
     * @param <T> 类型
     * @return 解码记录
     */
    private <T> T decode(String value, Class<T> type) {
        try {
            return json.decode(value, type);
        } catch (RuntimeException invalid) {
            throw new TravelStorageFailure(Code.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 将SQL异常变为固定依赖错误，不携带底层异常或参数。
     *
     * @param operation 本机SQL操作
     * @param <T> 结果类型
     * @return SQL结果
     */
    private <T> T database(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException unavailable) {
            throw new TravelStorageFailure(Code.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 写入及加锁读取必须加入应用短事务，避免追加成功而指针更新未提交。 */
    private void requireTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new TravelStorageFailure(Code.DEPENDENCY_UNAVAILABLE);
    }
}
