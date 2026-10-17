package com.lawrence.travel.domain.memory;

import java.util.List;
import java.util.UUID;

/** 可追溯的摘录式摘要，不允许模型生成新的约束、偏好或授权。
 * @param excerpts 已验证的原文摘录；未摘录消息仍以完整来源引用保存 */
public record ConversationDigest(List<Excerpt> excerpts) {
    /** 复制有界摘录；摘要只是历史数据，不能改变权威状态。 */
    public ConversationDigest { if (excerpts == null || excerpts.size() > 8) throw new IllegalArgumentException("SUMMARY_INVALID"); excerpts = List.copyOf(excerpts); }
    /** @param messageId 私有原消息 @param quote 必须是来源连续子串，最多300字符 */
    public record Excerpt(UUID messageId, String quote) {
        /** 拒绝空身份、空摘录及超长输出，不截断后当作完整结果。 */
        public Excerpt { if (messageId == null || quote == null || quote.isBlank() || quote.length() > 300) throw new IllegalArgumentException("SUMMARY_EXCERPT_INVALID"); }
    }
    /** @param originals 本次实际送给摘要器的原文 @return 校验后的同一摘要；旧来源或编造内容均拒绝 */
    public ConversationDigest verified(java.util.Map<UUID, String> originals) {
        if (excerpts.stream().anyMatch(e -> !originals.containsKey(e.messageId()) || !originals.get(e.messageId()).contains(e.quote()))
                || excerpts.stream().map(Excerpt::messageId).distinct().count() != excerpts.size()) throw new IllegalArgumentException("SUMMARY_SOURCE_MISMATCH");
        return this;
    }
}
