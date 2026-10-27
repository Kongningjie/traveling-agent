package com.lawrence.travel.application.safety;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 输入、上下文、摘要、工具结果及最终回复共用的基础确定性安全边界。
 *
 * <p>DialogueService.process在模型前后检查；Harness运行时和资料应用层在各自输入/输出处复查，
 * 必须完整检查后才交付用户。检查危险载荷和进程内已知秘密，拒绝或检查不可用均失败关闭，
 * 只把固定分类/策略版本/问题ID交给reporter，不记录被拦截原文。
 * 本类不是旅行语义意图识别器，也不冒充完整内容审核或权限系统；业务授权仍由应用服务决定。
 */
public final class SafetyGate {
    /** 基础策略变化时提升版本，便于定位检查结果。 */
    public static final String VERSION = "text-boundary-v1";

    /** 检查拒绝和检查不可用必须分开处理。 */
    public enum Status {
        /** 通过基础检查。 */
        ALLOW,
        /** 明确拒绝。 */
        REJECT,
        /** 检查不可用。 */
        UNAVAILABLE
    }

    /**
     * @param status 结果
     * @param reason 固定分类
     * @param problemId 问题编号，通过时为空
     * @param policyVersion 策略
     * @param safeNextAction 固定安全动作
     */
    public record Result(
            Status status,
            String reason,
            UUID problemId,
            String policyVersion,
            String safeNextAction) {}

    private static final Pattern DANGEROUS =
            Pattern.compile(
                    "(?is)<\\s*(script|iframe|object)\\b|(?:javascript|vbscript)\\s*:|data\\s*:\\s*text/html|on(?:error|load)\\s*=|(?:authorization\\s*[:=]\\s*bearer\\s+)[a-zA-Z0-9._-]{8,}|\\bsk-[a-zA-Z0-9_-]{24,}");
    private final Supplier<List<String>> secrets;
    private final BiConsumer<String, Result> reporter;

    /**
     * @param secrets 仅在进程内读取凭据，不进入提示词／记录
     * @param reporter 只接收固定分类和问题ID
     */
    public SafetyGate(Supplier<List<String>> secrets, BiConsumer<String, Result> reporter) {
        this.secrets = secrets;
        this.reporter = reporter;
    }

    /**
     * @return 离线基础策略，不依赖模型评分
     */
    public static SafetyGate local() {
        return new SafetyGate(List::of, (point, result) -> {});
    }

    /**
     * 对完整文本运行本地基础策略及已知秘密检查，返回ALLOW、REJECT或UNAVAILABLE。
     *
     * <p>规则只判定基础文本边界，不把敏感词当旅行操作意图。失败时reporter接收固定结果，
     * 记录通道异常也转为UNAVAILABLE，不能因为诊断失败放行。不会保存原文或密钥本身。
     *
     * @param point 固定检查点
     * @param text 待查完整文本
     * @return 不保存原文的检查结果；依赖异常失败关闭
     */
    public Result check(String point, String text) {
        Result result;
        try {
            boolean invalid =
                    text == null
                            || text.length() > 262144
                            || text.indexOf('\0') >= 0
                            || DANGEROUS.matcher(text).find();
            boolean secret =
                    text != null
                            && secrets.get().stream()
                                    .filter(s -> s != null && s.length() >= 8)
                                    .anyMatch(text::contains);
            result =
                    invalid || secret
                            ? denied(Status.REJECT, secret ? "SECRET_DETECTED" : "UNSAFE_TEXT")
                            : new Result(Status.ALLOW, "", null, VERSION, "CONTINUE");
        } catch (RuntimeException unavailable) {
            result = denied(Status.UNAVAILABLE, "CHECK_UNAVAILABLE");
        }
        if (result.status() != Status.ALLOW) {
            try {
                reporter.accept(point, result);
            } catch (RuntimeException unavailable) {
                result =
                        new Result(
                                Status.UNAVAILABLE,
                                "CHECK_UNAVAILABLE",
                                result.problemId(),
                                VERSION,
                                "RETRY_OR_WITHDRAW");
            }
        }
        return result;
    }

    /**
     * 为调用链提供失败关闭的检查入口：非ALLOW立即抛类型化Failure，阻止后续发送、显示或提交。
     *
     * <p>调用方按安全分类暂停并保留可处理状态，不向用户回显被拦截文本；完整输出先检查后展示，
     * 不能通过先流式发送再检查来声称已经拦截。
     *
     * @param point 固定检查点
     * @param text 完整文本；失败抛固定异常，不能先显示后拦截
     */
    public void require(String point, String text) {
        var result = check(point, text);
        if (result.status() != Status.ALLOW) throw new Failure(result);
    }

    /**
     * @param status 拒绝／不可用
     * @param reason 固定分类
     * @return 无原文的失败
     */
    private Result denied(Status status, String reason) {
        return new Result(status, reason, UUID.randomUUID(), VERSION, "RETRY_OR_WITHDRAW");
    }

    /** 类型化错误，跨Agent边界保留编号而不保留任意异常正文。 */
    public static final class Failure extends RuntimeException {
        private final Result result;

        /**
         * @param result 已脱敏结果
         */
        public Failure(Result result) {
            super("SAFETY_" + result.status().name());
            this.result = result;
        }

        /**
         * @return 可展示的固定结果，不含被拦截文本
         */
        public Result result() {
            return result;
        }
    }
}
