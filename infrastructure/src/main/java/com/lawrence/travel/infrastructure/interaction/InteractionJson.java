package com.lawrence.travel.infrastructure.interaction;

import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.infrastructure.travel.TravelJson;

/** 复用严格规范化编解码工具，不依赖其他业务适配器。 */
public final class InteractionJson implements InteractionCodec {
    private final TravelJson json;

    /**
     * @param json 无凭据的线程安全 JSON 工具
     */
    public InteractionJson(TravelJson json) {
        this.json = json;
    }

    /**
     * {@inheritDoc} 不打印正文。
     */
    @Override
    public String encode(Object value) {
        return json.encode(value);
    }

    /**
     * {@inheritDoc} 解析失败使用固定错误。
     */
    @Override
    public <T> T decode(String text, Class<T> type) {
        if (type == com.lawrence.travel.domain.interaction.DialogueIntent.Result.class)
            return type.cast(json.decodeDialogue(text));
        return json.decode(text, type);
    }
}
