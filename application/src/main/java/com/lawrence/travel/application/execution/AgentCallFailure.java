package com.lawrence.travel.application.execution;

/** 跨模型端口传递固定失败码与恢复策略；不保留供应商正文、原异常或原因链。 */
public class AgentCallFailure extends RuntimeException {
    /** 应用允许识别的固定模型失败类别。 */
    public enum Code {
        MODEL_OUTPUT_INVALID,
        MODEL_OUTPUT_MISSING,
        MODEL_OUTPUT_UNSTRUCTURED,
        MODEL_OUTPUT_ENVELOPE,
        MODEL_OUTPUT_EMPTY,
        MODEL_OUTPUT_SCHEMA,
        MODEL_OUTPUT_TRUNCATED,
        MODEL_ACCESS_DENIED,
        MODEL_NOT_FOUND,
        MODEL_INVALID_PARAMETER,
        MODEL_OPTIONS_REJECTED,
        MODEL_ENDPOINT_REJECTED,
        MODEL_INPUT_TOO_LARGE,
        MODEL_INPUT_INVALID,
        MODEL_MEDIA_NOT_ALLOWED,
        MODEL_ID_REJECTED,
        MODEL_FREE_QUOTA_EXHAUSTED,
        MODEL_CONTEXT_LIMIT_SAFE,
        MODEL_CONTEXT_LIMIT,
        MODEL_RATE_LIMITED,
        MODEL_TRANSPORT_FAILED,
        MODEL_STREAM_INVALID,
        MODEL_STREAM_INCOMPLETE,
        MODEL_STREAM_TOO_LARGE,
        MODEL_STREAM_PROVIDER_ERROR,
        MODEL_CANCELLED,
        MODEL_TIMEOUT,
        MODEL_INTERRUPTED,
        MODEL_EXECUTION_FAILED,
        MODEL_PROVIDER_HTTP
    }

    /** 运行状态转换由应用执行器落实，不授权自动恢复或创建新预算。 */
    public enum Recovery {
        /** 确定性协议错误，结束本次运行。 */
        STOP,
        /** 已确认零用量的暂时失败，在原尝试上限和预算内重试。 */
        RETRY,
        /** 等待用户或配置修正，不自动发送下一请求。 */
        PAUSE,
        /** 上下文容量不足，按预算暂停处理。 */
        PAUSE_BUDGET
    }

    private final Code code;
    private final int httpStatus;
    private final boolean knownZeroUsage;

    /**
     * 创建固定码失败；未提供零用量证据时禁止把暂时失败自动重放。
     *
     * @param code 固定类别
     */
    public AgentCallFailure(Code code) {
        this(code.name(), false);
    }

    /**
     * 只接受枚举名或固定HTTP数字码，其余内容归入未知执行失败且不保存。
     *
     * @param diagnostic 内部适配器提供的固定分类，不是供应商自由文本
     * @param knownZeroUsage 当前失败请求是否明确报告零Token用量
     */
    public AgentCallFailure(String diagnostic, boolean knownZeroUsage) {
        super(safeDiagnostic(diagnostic), null, false, true);
        this.httpStatus = status(diagnostic);
        this.code = httpStatus == 0 ? parse(diagnostic) : Code.MODEL_PROVIDER_HTTP;
        this.knownZeroUsage = knownZeroUsage;
    }

    /**
     * 返回固定类别，HTTP状态通过独立的受限数字字段保留。
     *
     * @return 固定分类
     */
    public final Code code() {
        return code;
    }

    /**
     * 返回可写入既有48字符错误列的固定码，不含原异常文本。
     *
     * @return 已校验的错误码
     */
    public final String diagnosticCode() {
        return code == Code.MODEL_PROVIDER_HTTP ? "MODEL_PROVIDER_HTTP_" + httpStatus : code.name();
    }

    /**
     * 读取本次请求的计量证据，不查询或修改历史UNKNOWN记录。
     *
     * @return 仅供应商明确报告零用量时为true
     */
    public final boolean knownZeroUsage() {
        return knownZeroUsage;
    }

    /**
     * 决定是否可自动重试；未知用量、取消和超时不会自动重放。
     *
     * @return 应用层恢复策略
     */
    public final Recovery recovery() {
        return switch (code) {
            case MODEL_OUTPUT_INVALID,
                            MODEL_OUTPUT_MISSING,
                            MODEL_OUTPUT_UNSTRUCTURED,
                            MODEL_OUTPUT_ENVELOPE,
                            MODEL_OUTPUT_EMPTY,
                            MODEL_OUTPUT_SCHEMA,
                            MODEL_OUTPUT_TRUNCATED ->
                    Recovery.STOP;
            case MODEL_CONTEXT_LIMIT_SAFE, MODEL_CONTEXT_LIMIT -> Recovery.PAUSE_BUDGET;
            case MODEL_RATE_LIMITED -> knownZeroUsage ? Recovery.RETRY : Recovery.PAUSE;
            case MODEL_PROVIDER_HTTP ->
                    knownZeroUsage
                                    && java.util.Set.of(408, 425, 429, 500, 502, 503, 504)
                                            .contains(httpStatus)
                            ? Recovery.RETRY
                            : Recovery.PAUSE;
            default -> Recovery.PAUSE;
        };
    }

    /**
     * 将任意输入收敛为固定枚举，避免诊断文本携带原始数据。
     *
     * @param diagnostic 待识别固定码
     * @return 已知枚举或固定未知分类
     */
    private static Code parse(String diagnostic) {
        try {
            var value = Code.valueOf(diagnostic == null ? "" : diagnostic);
            return value == Code.MODEL_PROVIDER_HTTP ? Code.MODEL_EXECUTION_FAILED : value;
        } catch (IllegalArgumentException invalid) {
            return Code.MODEL_EXECUTION_FAILED;
        }
    }

    /**
     * 仅解析固定前缀后的三位HTTP状态，不保留其他后缀。
     *
     * @param diagnostic 内部固定码
     * @return 100到599，或0表示非HTTP分类
     */
    private static int status(String diagnostic) {
        return diagnostic != null && diagnostic.matches("MODEL_PROVIDER_HTTP_[1-5][0-9]{2}")
                ? Integer.parseInt(diagnostic.substring("MODEL_PROVIDER_HTTP_".length()))
                : 0;
    }

    /**
     * 构造异常消息前完成固定码归一化。
     *
     * @param diagnostic 适配器分类
     * @return 不含任意外部文本的消息
     */
    private static String safeDiagnostic(String diagnostic) {
        int http = status(diagnostic);
        return http == 0 ? parse(diagnostic).name() : "MODEL_PROVIDER_HTTP_" + http;
    }
}
