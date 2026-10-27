package com.lawrence.travel.application.identity;

/** 身份用例固定失败分类；不附带输入、哈希、会话或底层依赖异常链。 */
public final class IdentityFailure extends RuntimeException {
    /** HTTP 层负责映射以下安全分类。 */
    public enum Code {
        /** 输入无效。 */
        INVALID_INPUT,
        /** 注册不能完成，不回显账号信息。 */
        REGISTRATION_REJECTED,
        /** 登录信息无效或账号停用，统一处理。 */
        INVALID_CREDENTIALS,
        /** 资源不存在或不属于当前用户。 */
        NOT_FOUND,
        /** 当前速率已超限。 */
        RATE_LIMITED,
        /** 数据库或 Redis 不可用，拒绝降级认证。 */
        DEPENDENCY_UNAVAILABLE
    }

    private final Code code;

    /**
     * 生成不含外部异常链的失败。
     * @param code 固定分类
     */
    public IdentityFailure(Code code) {
        super(java.util.Objects.requireNonNull(code).name());
        this.code = code;
    }

    /**
     * @return 可映射为安全响应的固定分类
     */
    public Code code() {
        return code;
    }
}
