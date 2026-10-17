package com.lawrence.travel.application.memory;

import com.lawrence.travel.domain.memory.ConversationDigest;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.UUID;

/** 上下文输入、来源及压缩审计契约；不携带框架对象或登录凭据。 */
public final class AgentContext {
    /** 命名空间。 */
    private AgentContext() { }
    /** @param text 规范文本 @return SHA256内容绑定；不依赖框架 */
    public static String hash(String text) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); } }
    /** @param constraintRevision 调用方实际读取需求版本；0仅供独立只读字段摘录 @param preferenceRevision 已使用偏好的版本，未使用时可空 */
    public record Basis(long constraintRevision, Long preferenceRevision) {
        /** 拒绝负版本，0只代表独立摘录，不代表忽略已提供的正版本。 */
        public Basis { if (constraintRevision < 0 || preferenceRevision != null && preferenceRevision < 0) throw new IllegalArgumentException("CONTEXT_BASIS_INVALID"); }
    }
    /** @param id 原消息 @param sequence 有序水位 @param text 原文 @param hash 来源正文及状态摘要 */
    public record Message(UUID id, long sequence, String text, String hash) { }
    /** @param scope 私有任务 @param runId 当前运行 @param role 固定角色 @param constraintRevision 需求版本
     * @param preferenceRevision 用户稳定版本 @param generation 执行代次 @param watermark 全任务消息水位
     * @param authority 结构化保护区JSON @param bindingHash 保护区及实际来源摘要 @param history 同偏好版本的历史，最多50条 */
    public record Source(TaskScope scope, UUID runId, String role, long constraintRevision, long preferenceRevision, long generation,
                         long watermark, String authority, String bindingHash, List<Message> history) {
        /** 来源集合不可在模型调用期间被改写。 */
        public Source { history = List.copyOf(history); }
    }
    /** @param id 摘要版本 @param sourceHash 精确上下文依赖 @param digest 已校验原文摘录 @param covered 完整覆盖来源
     * @param beforeTokens 首次创建摘要时压缩前保守估算 @param afterTokens 首次创建时压缩后估算 @param protectedHash 未经摘要的保护区摘要 @param audit 可读依赖版本与原请求指纹 */
    public record Summary(UUID id, String sourceHash, ConversationDigest digest, List<MessageRef> covered, long beforeTokens, long afterTokens, String protectedHash, Audit audit) {
        /** 不保存第二份历史全文；正文由私有来源按需读取。 */
        public Summary { covered = List.copyOf(covered); }
        /** @param tokens 最终序列化后的保守估算 @return 保留身份和依赖，只修正本次创建的审计值 */
        public Summary after(long tokens) { return new Summary(id, sourceHash, digest, covered, beforeTokens, tokens, protectedHash, audit); }
    }
    /** @param constraintRevision 需求版本 @param preferenceRevision 偏好版本 @param generation 执行代次 @param watermark 来源水位
     * @param requestHash 首次使用该摘要的完整请求指纹 @param versions 当前／候选产物引用元信息 @param evidenceReferences 精确证据引用、状态与有效期 */
    public record Audit(long constraintRevision, long preferenceRevision, long generation, long watermark, String requestHash, Object versions, List<?> evidenceReferences) {
        /** 依赖集合不可共享可变列表；不复制偏好正文进审计元信息。 */
        public Audit { evidenceReferences = List.copyOf(evidenceReferences); }
    }
    /** @param id 原消息 @param sequence 原顺序 @param hash 正文和状态摘要 */
    public record MessageRef(UUID id, long sequence, String hash) { }
    /** @param prompt 完整且通过预算检查的输入 @param source 发送前再次核对的来源 @param summaryId 摘要版本，可空 */
    public record Prepared(String prompt, Source source, UUID summaryId) { }
    /** 框架压缩回调；实现必须使用原运行的受计量模型，不得启用memory写入。 */
    public interface Compression {
        /** @param source 压缩输入的实际版本；框架适配器据此配置逐请求检查 */
        default void bind(Source source) { }
        /** @param text 完整输入 @return 框架估算与保守文本上界的较大值，包含消息模板预留 */
        long estimate(String text);
        /** @param messages 仅历史且已按输入预算选定的消息 @return 摘录式摘要；主请求和保护区永不送入压缩器 */
        ConversationDigest compact(List<Message> messages);
    }
}
