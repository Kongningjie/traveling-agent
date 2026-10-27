package com.lawrence.travel.application.document;

/**
 * 初始资源配置；首轮只允许收紧冻结硬上限，调高需另行Review、测试及必要迁移。
 * @param bytes 单文件字节
 * @param pages PDF页数
 * @param text 单文件字符
 * @param expanded ZIP展开字节
 * @param seconds 墙钟秒
 * @param perChat 聊天有效附件
 * @param userBytes 用户原件字节
 * @param userText 用户文本及预留
 */
public record DocumentLimits(
        int bytes,
        int pages,
        int text,
        long expanded,
        int seconds,
        int perChat,
        long userBytes,
        long userText) {
    public static final DocumentLimits DEFAULT =
            new DocumentLimits(10485760, 100, 200000, 52428800, 60, 10, 209715200, 1000000);

    /** 拒绝无界、负数或超过当前迁移／解析协议能力的配置。 */
    public DocumentLimits {
        if (bytes < 1
                || bytes > 10485760
                || pages < 1
                || pages > 100
                || text < 1
                || text > 200000
                || expanded < 1
                || expanded > 52428800
                || seconds < 1
                || seconds > 60
                || perChat < 1
                || perChat > 10
                || userBytes < 1
                || userBytes > 209715200
                || userText < 1
                || userText > 1000000)
            throw new IllegalArgumentException("DOCUMENT_LIMITS_INVALID");
    }

    /**
     * @param source 受信配置提供器，不读取模型数据
     * @return 有边界的初始限制
     */
    public static DocumentLimits from(java.util.function.Function<String, String> source) {
        return new DocumentLimits(
                (int) value(source, "BYTES", 10485760),
                (int) value(source, "PAGES", 100),
                (int) value(source, "TEXT", 200000),
                value(source, "EXPANDED", 52428800),
                (int) value(source, "SECONDS", 60),
                (int) value(source, "PER_CHAT", 10),
                value(source, "USER_BYTES", 209715200),
                value(source, "USER_TEXT", 1000000));
    }

    /**
     * @param source 受信配置
     * @param key 固定后缀
     * @param fallback 默认
     * @return 有界数值，错误不回显配置原值
     */
    private static long value(
            java.util.function.Function<String, String> source, String key, long fallback) {
        String text = source.apply("DOCUMENT_MAX_" + key);
        try {
            long result = text == null || text.isBlank() ? fallback : Long.parseLong(text);
            if (result < 1 || result > 209715200) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("DOCUMENT_LIMITS_INVALID");
        }
    }

    /**
     * @return 解析容器只允许这些非凭据数值进入环境，不继承runner或后端秘密
     */
    public java.util.Map<String, Long> environment() {
        return java.util.Map.of(
                "DOCUMENT_MAX_BYTES",
                (long) bytes,
                "DOCUMENT_MAX_PAGES",
                (long) pages,
                "DOCUMENT_MAX_TEXT",
                (long) text,
                "DOCUMENT_MAX_EXPANDED",
                expanded,
                "DOCUMENT_MAX_SECONDS",
                (long) seconds,
                "DOCUMENT_MAX_PER_CHAT",
                (long) perChat,
                "DOCUMENT_MAX_USER_BYTES",
                userBytes,
                "DOCUMENT_MAX_USER_TEXT",
                userText);
    }
}
