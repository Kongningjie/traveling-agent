package com.lawrence.travel.application.evaluation;

import com.lawrence.travel.application.artifact.ArtifactService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.application.travel.TravelStorageFailure.Code;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 保存程序事实和认证用户复核；不调用模型、不改业务结果、不发布Langfuse数据。 */
public final class EvaluationScoringService {
    private final EvaluationScoring.Store store;
    private final BusinessTransactions transactions;
    private final Clock clock;

    /**
     * 装配权威评分仓储和短事务，不创建后台执行器。
     *
     * @param store PG评分仓储
     * @param transactions 同源短事务
     * @param clock 服务端时钟
     */
    public EvaluationScoringService(
            EvaluationScoring.Store store, BusinessTransactions transactions, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 仅供可信执行器登记完成事实；HTTP/Agent工具不能调用此入口伪造程序成绩。
     *
     * @param owner 原运行归属
     * @param source 来自执行器和固定判据的事实
     * @throws TravelStorageFailure 来源无效、归属不符、到期或同ID内容冲突
     */
    public void record(UUID owner, EvaluationScoring.Source source) {
        require(owner != null && source != null);
        try {
            EvaluationScorecard.assess(
                    source.context(),
                    source.row(),
                    source.oracle(),
                    source.checks(),
                    null,
                    clock.instant());
            require(
                    source.row().reason() == null
                            || source.row().reason().matches("[A-Z0-9_]{0,80}"));
            require(
                    source.row().trace().size() <= 60
                            && source.row().trace().stream()
                                    .allMatch(v -> v.matches("[A-Za-z0-9_.:-]{1,80}")));
            require(
                    source.row().metrics().size() <= 60
                            && source.row().metrics().keySet().stream()
                                    .allMatch(v -> v.matches("[A-Za-z0-9_.-]{1,64}")));
            String fingerprint =
                    EvaluationScorecard.fingerprint(
                            source.context(), source.row(), source.checks());
            transactions.required(
                    () -> {
                        if (!store.record(owner, source, fingerprint))
                            throw fail(Code.IDEMPOTENCY_CONFLICT);
                        return null;
                    });
        } catch (IllegalArgumentException invalid) {
            throw fail(Code.INVALID_INPUT);
        }
    }

    /**
     * 读取认证账号拥有的评分状态，不暴露其他账号的运行是否存在。
     *
     * @param actor 服务端认证身份
     * @param run 原运行
     * @return 当前程序和人工结论，未评审不自动通过
     * @throws TravelStorageFailure 不可见或来源无效
     */
    public EvaluationScoring.View read(UserIdentity actor, UUID run) {
        require(actor != null && run != null);
        return transactions.required(
                () -> {
                    var saved = visible(store.read(actor.id(), run, false));
                    var review = saved.latest() == null ? null : saved.latest().review();
                    var result = assessment(saved, review);
                    return new EvaluationScoring.View(
                            run,
                            saved.source().row().caseId(),
                            saved.source().row().mode(),
                            saved.fingerprint(),
                            saved.revision(),
                            result.program(),
                            result.overall(),
                            saved.latest());
                });
    }

    /**
     * 幂等追加用户确认的评分；新评分不覆盖程序事实，也不延长run到期时间。
     *
     * @param actor 服务端认证账号，不接受请求体userId
     * @param run 原运行
     * @param command 用户明确提交的分项和预期修订
     * @return 本次回执；同请求原样重放返回原回执且不恢复旧版本
     * @throws TravelStorageFailure 参数非法、来源不匹配、修订过期或资源不可见
     */
    public EvaluationScoring.Receipt review(
            UserIdentity actor, UUID run, EvaluationScoring.Command command) {
        validate(actor, run, command);
        String requestHash = requestHash(actor.id(), run, command);
        return transactions.required(
                () -> {
                    // 与撤销/到期清理使用同一run锁，再读取评分来源；不在事务中做网络请求。
                    var saved = visible(store.read(actor.id(), run, true));
                    var existing = store.receipt(actor.id(), command.requestId(), requestHash);
                    if (existing != null) return existing;
                    if (saved.revision() != command.expectedRevision())
                        throw fail(Code.STALE_VERSION);
                    if (!saved.fingerprint().equals(command.fingerprint()))
                        throw fail(Code.STALE_VERSION);
                    if (saved.source().row().status().equals("NOT_RUN"))
                        throw fail(Code.INVALID_INPUT);
                    String confirmationHash =
                            hash(actor.id() + "\n" + requestHash + "\n" + clock.instant());
                    var review =
                            new EvaluationScorecard.Review(
                                    saved.fingerprint(),
                                    confirmationHash,
                                    EvaluationScorecard.RUBRIC_VERSION,
                                    command.conditions(),
                                    command.facts(),
                                    command.clarification(),
                                    command.usability(),
                                    command.taskCompleted(),
                                    command.majorViolation());
                    var result = assessment(saved, review);
                    var receipt =
                            new EvaluationScoring.Receipt(
                                    command.requestId(),
                                    run,
                                    saved.revision() + 1,
                                    review,
                                    result.program(),
                                    result.overall(),
                                    command.reasons().stream().sorted().toList());
                    if (!store.append(actor.id(), saved.revision(), requestHash, receipt))
                        throw fail(Code.STALE_VERSION);
                    return receipt;
                });
    }

    /**
     * 重新计算可信程序结论，并校验来源指纹；不信任持久层中另存的通过标记。
     *
     * @param saved 原始证据
     * @param review 人工记录；未评审为null
     * @return 重新计算的分层结论
     */
    private EvaluationScorecard.Assessment assessment(
            EvaluationScoring.Stored saved, EvaluationScorecard.Review review) {
        var source = saved.source();
        if (!saved.fingerprint()
                .equals(
                        EvaluationScorecard.fingerprint(
                                source.context(), source.row(), source.checks())))
            throw fail(Code.DEPENDENCY_UNAVAILABLE);
        try {
            return EvaluationScorecard.assess(
                    source.context(),
                    source.row(),
                    source.oracle(),
                    source.checks(),
                    review,
                    clock.instant());
        } catch (IllegalArgumentException invalid) {
            throw fail(Code.NOT_FOUND);
        }
    }

    /**
     * 校验人工输入；分值、确认、修订和依据都不能由程序默认补齐。
     *
     * @param actor 认证身份
     * @param run 原运行
     * @param command 评分请求
     */
    private void validate(UserIdentity actor, UUID run, EvaluationScoring.Command command) {
        require(actor != null && run != null && command != null);
        require(
                command.requestId() != null
                        && command.expectedRevision() >= 0
                        && command.confirmed()
                        && EvaluationRetention.hash(command.fingerprint())
                        && command.reasons() != null
                        && !command.reasons().isEmpty()
                        && command.reasons().size() <= 6
                        && command.reasons().stream().noneMatch(Objects::isNull)
                        && new HashSet<>(command.reasons()).size() == command.reasons().size());
        for (int score :
                List.of(
                        command.conditions(),
                        command.facts(),
                        command.clarification(),
                        command.usability())) require(score >= 0 && score <= 2);
        require(
                !command.reasons().contains(EvaluationScoring.Reason.OK)
                        || command.reasons().size() == 1);
        require(
                command.majorViolation()
                        == command.reasons().contains(EvaluationScoring.Reason.MAJOR_VIOLATION));
        if (command.reasons().contains(EvaluationScoring.Reason.OK))
            require(
                    command.taskCompleted()
                            && !command.majorViolation()
                            && command.conditions() == 2
                            && command.facts() == 2
                            && command.clarification() == 2
                            && command.usability() == 2);
    }

    /**
     * 对规范化请求计算幂等指纹；字段只允许UUID/哈希/整数/布尔/枚举，没有分隔符歧义。
     *
     * @param owner 认证归属
     * @param run 独立运行
     * @param c 已校验请求
     * @return SHA256
     */
    private String requestHash(UUID owner, UUID run, EvaluationScoring.Command c) {
        return hash(
                String.join(
                        "\n",
                        owner.toString(),
                        run.toString(),
                        c.requestId().toString(),
                        Long.toString(c.expectedRevision()),
                        c.fingerprint(),
                        Boolean.toString(c.confirmed()),
                        Integer.toString(c.conditions()),
                        Integer.toString(c.facts()),
                        Integer.toString(c.clarification()),
                        Integer.toString(c.usability()),
                        Boolean.toString(c.taskCompleted()),
                        Boolean.toString(c.majorViolation()),
                        c.reasons().stream()
                                .sorted()
                                .map(Enum::name)
                                .collect(java.util.stream.Collectors.joining(","))));
    }

    /**
     * 对固定技术字段生成摘要，不向响应返回认证账号原值。
     *
     * @param value 技术字段串
     * @return SHA256
     */
    private String hash(String value) {
        return ArtifactService.hash(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 不可见资源统一拒绝。
     *
     * @param value 可空仓储结果
     * @return 可见来源
     */
    private EvaluationScoring.Stored visible(EvaluationScoring.Stored value) {
        if (value == null) throw fail(Code.NOT_FOUND);
        return value;
    }

    /**
     * 拒绝非法输入，不回显评分材料。
     *
     * @param valid 是否符合协议
     */
    private void require(boolean valid) {
        if (!valid) throw fail(Code.INVALID_INPUT);
    }

    /**
     * 创建无底层异常的固定错误。
     *
     * @param code 分类
     * @return 固定异常
     */
    private TravelStorageFailure fail(Code code) {
        return new TravelStorageFailure(code);
    }
}
