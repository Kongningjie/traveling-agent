package com.lawrence.travel.application.port;

/** 协商 JSON 边界，不将 Jackson 或 Harness 带入应用层。 */
public interface InteractionCodec {
    /**
     * @param value 有界产物
     * @return 规范 JSON
     */
    String encode(Object value);

    /**
     * @param text 不可信 JSON
     * @param type 明确目标
     * @param <T> 目标类型
     * @return 严格结果；错误不回显正文
     */
    <T> T decode(String text, Class<T> type);
}
