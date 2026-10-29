package com.lawrence.travel.application.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 程序证据和人工复核协议；只接受执行器登记证据，不向浏览器开放原报告写入口。 */
public final class EvaluationScoring {
    /** 不实例化协议容器。 */
    private EvaluationScoring() {}

    /** 结构化评分依据，避免把聊天或凭据写进评分备注。 */
    public enum Reason {
        /** 符合预期。 */
        OK,
        /** 条件遗漏或误改。 */
        CONDITIONS,
        /** 事实或引用有误。 */
        FACTS,
        /** 澄清不合理。 */
        CLARIFICATION,
        /** 回复可用性不足。 */
        USABILITY,
        /** 存在重大违规。 */
        MAJOR_VIOLATION
    }

    /**
     * 可信执行器提交的程序证据；不含输入、输出、附件或模型自评分。
     *
     * @param context 原始运行、配置及完整执行证据指纹
     * @param row 执行器报告行
     * @param oracle 固定用例判据，不能由模型或人工评分请求覆盖
     * @param checks 程序检查结果
     */
    public record Source(
            EvaluationScorecard.Context context,
            Evaluation.Row row,
            Evaluation.Oracle oracle,
            Map<String, Boolean> checks) {
        /** 拷贝可变集合，禁止登记后在调用方修改程序事实。 */
        public Source {
            if (context == null || row == null || oracle == null || checks == null)
                throw new IllegalArgumentException("EVALUATION_SOURCE_INVALID");
            row =
                    new Evaluation.Row(
                            row.caseId(),
                            row.partition(),
                            row.repetition(),
                            row.mode(),
                            row.status(),
                            row.outcome(),
                            List.copyOf(row.failedChecks()),
                            Map.copyOf(row.metrics()),
                            List.copyOf(row.trace()),
                            row.reason());
            // required是集合语义，固定排序避免进程重启后Set迭代顺序改变而误报幂等冲突。
            oracle =
                    new Evaluation.Oracle(
                            oracle.outcome(),
                            java.util.Collections.unmodifiableSortedSet(
                                    new java.util.TreeSet<>(oracle.required())));
            checks = Map.copyOf(checks);
        }
    }

    /**
     * 认证用户明确提交的评分；不接受浏览器提供归属、程序状态或综合结论。
     *
     * @param requestId 幂等键
     * @param expectedRevision 用户看到的评分修订号，首次为0
     * @param fingerprint 用户复核的完整程序证据指纹
     * @param confirmed 用户明确确认本次提交
     * @param conditions 条件保留0～2
     * @param facts 事实与引用0～2
     * @param clarification 澄清合理性0～2
     * @param usability 回复可用性0～2
     * @param taskCompleted 用户判断任务是否完成
     * @param majorViolation 用户标记重大违规
     * @param reasons 至少一个结构化依据，最多6项，不允许重复
     */
    public record Command(
            UUID requestId,
            long expectedRevision,
            String fingerprint,
            boolean confirmed,
            int conditions,
            int facts,
            int clarification,
            int usability,
            boolean taskCompleted,
            boolean majorViolation,
            List<Reason> reasons) {
        /** 固定调用时的依据列表；非法null成员由服务统一拒绝。 */
        public Command {
            if (reasons != null)
                reasons =
                        java.util.Collections.unmodifiableList(new java.util.ArrayList<>(reasons));
        }
    }

    /**
     * 持久人工记录；权限来自调用入口认证，不把“已登录”等同于技术上证明真人。
     *
     * @param requestId 幂等请求
     * @param runId 原始运行
     * @param revision 本次评分修订
     * @param review 四项评分及服务器生成的确认记录指纹
     * @param program 原程序门禁
     * @param overall 综合结论，程序失败不能被人工高分抵消
     * @param reasons 结构化依据
     */
    public record Receipt(
            UUID requestId,
            UUID runId,
            long revision,
            EvaluationScorecard.Review review,
            EvaluationScorecard.Status program,
            EvaluationScorecard.Status overall,
            List<Reason> reasons) {
        /** 防止响应调用方修改已保存的评分依据。 */
        public Receipt {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * 私有证据与最新人工记录；存储读取已做归属、到期和撤销过滤。
     *
     * @param source 不可变程序来源
     * @param fingerprint 来源指纹
     * @param revision 当前评分修订，初始为0
     * @param latest 最近提交的人工评分；尚未评分时为null
     */
    public record Stored(Source source, String fingerprint, long revision, Receipt latest) {}

    /**
     * 用户可见的最小评分状态；完整执行材料须在对应受控证据界面复核，不能只凭摘要评分。
     *
     * @param runId 独立运行
     * @param caseId 固定用例
     * @param mode 原执行模式
     * @param fingerprint 本次复核绑定的来源
     * @param revision 当前评分修订
     * @param program 程序门禁
     * @param overall 综合结论
     * @param latest 最近评分；未评为null
     */
    public record View(
            UUID runId,
            String caseId,
            Evaluation.Mode mode,
            String fingerprint,
            long revision,
            EvaluationScorecard.Status program,
            EvaluationScorecard.Status overall,
            Receipt latest) {}

    /** PG短事务端口；写入时须再次校验run归属和期限。 */
    public interface Store {
        /**
         * 幂等保存可信证据，不允许修改已保存来源。
         *
         * @param owner 运行归属
         * @param source 可信执行事实
         * @param fingerprint 来源指纹
         * @return 是否首次登记或内容完全一致
         */
        boolean record(UUID owner, Source source, String fingerprint);

        /**
         * 读取自有活跃来源；写评分时按run→评分来源的顺序加锁。
         *
         * @param owner 认证账号
         * @param run 原运行
         * @param lock 是否加事务行锁
         * @return 来源及当前评分；缺失/越权/到期/撤销为null
         */
        Stored read(UUID owner, UUID run, boolean lock);

        /**
         * 查本账号已提交的幂等请求，到期/撤销后不返回旧回执。
         *
         * @param owner 认证账号
         * @param requestId 幂等键
         * @param requestHash 请求内容指纹
         * @return 原回执；不存在为null，不同内容必须报冲突
         */
        Receipt receipt(UUID owner, UUID requestId, String requestHash);

        /**
         * 追加评分并CAS推进修订；调用者必须持有run和来源锁，全部写入同一短事务。
         *
         * @param owner 认证账号
         * @param expectedRevision 用户看到的修订
         * @param requestHash 本次请求指纹
         * @param receipt 服务器计算的回执
         * @return 是否在仍有效的run下完成追加
         */
        boolean append(UUID owner, long expectedRevision, String requestHash, Receipt receipt);
    }
}
