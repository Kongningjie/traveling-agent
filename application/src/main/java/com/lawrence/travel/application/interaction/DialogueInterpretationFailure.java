package com.lawrence.travel.application.interaction;

/** 理解适配器的脱敏失败契约；只保留白名单分类，不保存原始异常、供应商输出或业务正文。 */
public final class DialogueInterpretationFailure extends RuntimeException {
    private final Code code;

    /** 模型调用与业务结构解码分别归类，不根据供应商异常文本猜测失败原因。 */
    public enum Code {
        /** 模型句柄建立或上下文准备失败。 */
        MODEL_START_FAILED,
        /** 已建立句柄的推理或框架协议失败。 */
        MODEL_EXECUTION_FAILED,
        /** 调用线程被中断，不能据此认定远程请求没有消耗。 */
        MODEL_INTERRUPTED,
        /** 等待结果超时，用量仍按原计量门禁处理。 */
        MODEL_TIMEOUT,
        /** 返回内容不能解码为合法业务结构，含字段/枚举契约不满足。 */
        RESULT_DECODE_FAILED
    }

    /**
     * @param code 固定分类；不接受原始异常作为cause或suppressed
     */
    public DialogueInterpretationFailure(Code code) {
        super("DIALOGUE_" + java.util.Objects.requireNonNull(code).name(), null, false, true);
        this.code = code;
    }

    /**
     * @return 白名单分类，不含模型输出或异常原文
     */
    public Code code() {
        return code;
    }
}
