package com.lawrence.travel.application.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 完整会话评测适配；执行与独立程序裁判分离，真实模式必须有原始计量及本次授权。 */
public final class SessionEvaluationExecutor implements Evaluation.Executor {
    private final Evaluation.Mode mode;
    private final Driver driver;
    private final Judge judge;
    private final boolean authorized;
    /** @param mode 本地替身或真实模型，不支持把真实工具单项与会话质量混算
     * @param driver 通过现有用例执行脚本，不能读取Oracle
     * @param judge 仅在执行后读取观察和独立裁判，不能发模型请求
     * @param authorized 当前真实范围授权；false时连Driver都不进入 */
    public SessionEvaluationExecutor(Evaluation.Mode mode, Driver driver, Judge judge, boolean authorized) {
        if (mode != Evaluation.Mode.LOCAL_CONTRACT && mode != Evaluation.Mode.REAL_MODEL) throw new IllegalArgumentException("SESSION_MODE_INVALID");
        this.mode = mode; this.driver = Objects.requireNonNull(driver); this.judge = Objects.requireNonNull(judge); this.authorized = authorized;
    }
    /** {@inheritDoc} 层次由本次装配绑定，不从案例文本或模型回复推断。 */
    @Override public Evaluation.Mode mode() { return mode; }
    /** {@inheritDoc} 用量缺失/暂停不能判成真实模型通过；人工质量结论由后续签名表另行汇总。 */
    @Override public Evaluation.Observation execute(Evaluation.Case input) {
        if (mode == Evaluation.Mode.REAL_MODEL && !authorized) return paused("PAUSED_NOT_AUTHORIZED", Map.of());
        var capture = driver.execute(input);
        if (mode == Evaluation.Mode.REAL_MODEL && capture.metrics().getOrDefault("unrecognizedModelRequests", 0L) > 0) return paused("PAUSED_MODEL_ID_MISMATCH", capture.metrics());
        // P24验证的是调用前拒绝：必须有独立门禁审计证明，不能为了凑“真实调用数”绕过免费保护。
        boolean verifiedGuard = input.id().equals("P24") && capture.outcome().equals("PAUSED_EXPECTED")
                && capture.metrics().getOrDefault("verifiedAdmissionDenials", 0L) > 0;
        if (mode == Evaluation.Mode.REAL_MODEL && capture.metrics().getOrDefault("actualModelRequests", 0L) <= 0 && !verifiedGuard) return paused("PAUSED_REAL_USAGE_MISSING", capture.metrics());
        if (mode == Evaluation.Mode.REAL_MODEL && capture.metrics().getOrDefault("unknownModelRequests", 0L) > 0) return paused("PAUSED_REAL_USAGE_UNKNOWN", capture.metrics());
        if (mode == Evaluation.Mode.REAL_MODEL && !capture.metrics().keySet().containsAll(java.util.Set.of("unknownModelRequests", "actualInputTokens", "actualOutputTokens"))) return paused("PAUSED_REAL_USAGE_INCOMPLETE", capture.metrics());
        if (capture.outcome().startsWith("PAUSED_") && !capture.outcome().equals("PAUSED_EXPECTED")) return paused(capture.outcome(), capture.metrics());
        return new Evaluation.Observation(capture.outcome(), Map.copyOf(judge.check(input.id(), capture)), capture.metrics(), capture.trace());
    }
    /** @param outcome 固定暂停分类 @param metrics 已发生的实测，不为未知用量补零 @return 明确未完成的观察 */
    private static Evaluation.Observation paused(String outcome, Map<String, Long> metrics) { return new Evaluation.Observation(outcome, Map.of(), metrics, List.of("SESSION_PAUSED")); }
    /** @param userId 合成执行身份 @param chatId 原会话 @param outcome 固定结果码 @param turns 完成轮数
     * @param state 执行后只读权威状态，不是规划Agent自评 @param metrics 真实用量/耗时，缺失不补零 @param trace 固定步骤码 */
    public record Capture(UUID userId, UUID chatId, String outcome, int turns, Map<String, Object> state, Map<String, Long> metrics, List<String> trace) {
        /** 观察不可变，避免裁判阶段被执行器修改；不保存原始提示词或凭据。 */
        public Capture { state = Map.copyOf(state); metrics = Map.copyOf(metrics); trace = List.copyOf(trace); }
    }
    /** 会话驱动端口，真实适配与本地替身共享同一脚本，不接收预期判定。 */
    public interface Driver {
        /** @param input 已封存输入 @return 本次执行后的实际观察；错误由Runner固定分类 */
        Capture execute(Evaluation.Case input);
    }
    /** 独立程序检查，只读取执行后状态；人工语义判断不由此接口冒充。 */
    public interface Judge {
        /** @param caseId 读取独立裁判的键 @param capture 权威执行证据 @return 每项确定性检查，缺项即不通过 */
        Map<String, Boolean> check(String caseId, Capture capture);
    }
}
