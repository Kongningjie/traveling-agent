package com.lawrence.travel.application.travel;

/**
 * 存储用例固定错误；不携带 SQL、正文或底层异常链。
 */
public final class TravelStorageFailure extends RuntimeException {
    private final Code code;
    /**
     * 对外映射分类，资源缺失和越权使用同一结果。
     */
    public enum Code {
        /**
         * 参数结构或大小不符合契约。
         */ INVALID_INPUT,
        /**
         * 私有资源或关联资源不可见。
         */ NOT_FOUND,
        /**
         * 并发更新使用了过期行版本。
         */ STALE_VERSION,
        /**
         * 同一请求键或快照身份承载不同内容。
         */ IDEMPOTENCY_CONFLICT,
        /**
         * 尚未建立对应证据存储，不能接纳悬空引用。
         */ UNSUPPORTED_REFERENCE,
        /**
         * 数据库故障，不泄露原始异常。
         */ DEPENDENCY_UNAVAILABLE
    }

    /**
     * @param code 固定失败分类
     */
    public TravelStorageFailure(Code code) { super(java.util.Objects.requireNonNull(code).name()); this.code = code; }
    /** @return 可确定处理的固定分类，不读取异常原文判断业务行为 */
    public Code code() { return code; }
}
