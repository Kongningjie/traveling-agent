package com.lawrence.travel.application.port;

/** 自适应单向密码哈希，调用方必须先执行长度规则，不能记录输入与输出。 */
public interface PasswordHasher {
    /**
     * 计算带随机盐的哈希。
     * @param raw 仅当前请求使用的原始密码
     * @return 只允许写入账号仓储的哈希
     */
    String hash(String raw);
    /**
     * 恒定算法验证，不返回存储凭据。
     * @param raw 原始密码
     * @param encoded 数据库哈希
     * @return 是否匹配
     */
    boolean matches(String raw, String encoded);
}
