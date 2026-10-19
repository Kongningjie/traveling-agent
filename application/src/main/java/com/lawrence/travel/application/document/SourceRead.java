package com.lawrence.travel.application.document;

import java.util.List;
import java.util.UUID;

/** 资料读取协议；逻辑位置不是文件路径，用户材料不能授予业务操作权限。 */
public final class SourceRead {
    /** 协议命名空间。 */
    private SourceRead() { }

    /** @param user 用户 @param chat 私有聊天 @param budget 原输入预算，可空表示浏览器读取 @param token 原尝试 @param revision 资料水位 @param preference 偏好水位 @param before 历史截止顺序 */
    public record Scope(UUID user, UUID chat, UUID budget, long token, long revision, long preference, long before) { }

    /** @param document 不可变原件版本 @param hash 原件哈希 @param parser 解析器版本 @param position 页或段 @param start 字符起点（含） @param end 终点（不含） */
    public record Locator(UUID document, String hash, String parser, int position, int start, int end) { }

    /** @param reference 服务端生成的引用ID @param locator 可核对原文定位 @param text 本次实际读取内容 @param truncated 该页段尚有未读内容 @param notice 提取限制 */
    public record Fragment(UUID reference, Locator locator, String text, boolean truncated, String notice) { }

    /** @param fragments 实际命中，不代表全文读取 @param complete 是否已遍历本次范围 @param notice 匹配策略和覆盖说明 */
    public record Matches(List<Fragment> fragments, boolean complete, String notice) {
        /** 读取结果不可变。 */
        public Matches { fragments = List.copyOf(fragments); }
    }

    /** @param id 真实输入ID @param sequence 顺序 @param text 用户原文 @param reply 有效助手回复 */
    public record Conversation(UUID id, long sequence, String text, String reply) { }
    /** @param path 逻辑工作文件 @param hash 正文哈希 @param characters 完整字符数；目录不携带全文 */
    public record WorkFile(String path, String hash, int characters) { }
}
