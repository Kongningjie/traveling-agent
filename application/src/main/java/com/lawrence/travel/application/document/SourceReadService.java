package com.lawrence.travel.application.document;

import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DocumentStore;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.SourceReadStore;
import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.domain.execution.BackgroundJob;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 检查资料归属，读取原文片段并保存引用。
 */
public final class SourceReadService {
    private final SourceReadStore sources;
    private final DocumentStore documents;
    private final JobStore jobs;
    private final BusinessTransactions tx;
    private final InteractionCodec json;
    private final SafetyGate safety;

    /**
     * @param sources 水位及引用
     * @param documents 附件索引
     * @param jobs READY判定
     * @param tx 短事务
     * @param json JSON
     * @param safety 文本检查
     */
    public SourceReadService(
            SourceReadStore sources,
            DocumentStore documents,
            JobStore jobs,
            BusinessTransactions tx,
            InteractionCodec json,
            SafetyGate safety) {
        this.sources = sources;
        this.documents = documents;
        this.jobs = jobs;
        this.tx = tx;
        this.json = json;
        this.safety = safety;
    }

    /**
     * @param budget 原输入预算
     * @param token 原尝试
     * @return 可从PG恢复的工作记忆目录，不把讨论内容当确认偏好
     */
    public List<SourceRead.WorkFile> files(UUID budget, long token) {
        return tx.required(() -> sources.files(sources.agent(budget, token)));
    }

    /**
     * @param budget 当前预算
     * @param token 原尝试
     * @return 默认目录，不注入任何全文
     */
    public List<Documents.View> list(UUID budget, long token) {
        return tx.required(
                () -> {
                    var s = sources.agent(budget, token);
                    return documents.list(scope(s)).stream()
                            .map(
                                    e -> {
                                        var r = report(s, e);
                                        return new Documents.View(
                                                e.id(),
                                                e.name(),
                                                e.kind(),
                                                e.size(),
                                                r == null ? "UNREADABLE" : "READY",
                                                r == null ? "DOCUMENT_NOT_READY" : "OK",
                                                r == null ? 0 : r.sections().size(),
                                                r == null ? "尚未完成读取。" : r.notice());
                                    })
                            .toList();
                });
    }

    /**
     * @param user 登录用户
     * @param chat 私有聊天
     * @param id 原件
     * @param query 1至80字面字符
     * @return 最多5段，每段最多1200字符；不把无命中写成事实
     */
    public SourceRead.Matches search(UUID user, UUID chat, UUID id, String query) {
        return tx.required(() -> search(sources.browser(user, chat), id, query));
    }

    /**
     * @param budget 原预算
     * @param token 原尝试
     * @param id 原件
     * @param query 中文等字面查询
     * @return 同聊天有界片段
     */
    public SourceRead.Matches search(UUID budget, long token, UUID id, String query) {
        return tx.required(() -> search(sources.agent(budget, token), id, query));
    }

    /**
     * @param s 锁定范围
     * @param id 不可变文档
     * @param query 不作正则或SQL通配符
     * @return 实际读取的命中，达到返回上限时说明不完整
     */
    private SourceRead.Matches search(SourceRead.Scope s, UUID id, String query) {
        if (query == null || query.isBlank() || query.length() > 80) throw invalid();
        safety.require("TOOL_INPUT", query);
        var e = documents.get(scope(s), id);
        var r = ready(s, e);
        var found = new ArrayList<SourceRead.Fragment>();
        for (var section : r.sections()) {
            int match = literalIndex(section.text(), query);
            if (match >= 0) {
                if (found.size() == 5)
                    return new SourceRead.Matches(found, false, "仅返回前5个命中页段，未阅读全文；用户材料并非官方证据。");
                int start = Math.max(0, match - 100);
                found.add(
                        fragment(
                                s,
                                e,
                                r,
                                section,
                                start,
                                Math.min(1200, section.text().length() - start)));
            }
        }
        return new SourceRead.Matches(found, true, "字面匹配，无命中不代表材料没有相关信息；仅检索指定文件。");
    }

    /**
     * @param user 登录用户
     * @param chat 私有聊天
     * @param id 原件
     * @param position 页段
     * @param start 起点
     * @param length 最多4000字符
     * @return 真实原文引用
     */
    public SourceRead.Fragment section(
            UUID user, UUID chat, UUID id, int position, int start, int length) {
        return tx.required(() -> section(sources.browser(user, chat), id, position, start, length));
    }

    /**
     * @param budget 原预算
     * @param token 原尝试
     * @param id 原件
     * @param position 页段
     * @param start 起点
     * @param length 最多4000字符
     * @return 有界片段
     */
    public SourceRead.Fragment section(
            UUID budget, long token, UUID id, int position, int start, int length) {
        return tx.required(
                () -> section(sources.agent(budget, token), id, position, start, length));
    }

    /**
     * @param s 私有范围
     * @param id 文档
     * @param position 实际页段
     * @param start 含起点
     * @param length 请求长度
     * @return 不伪造未读取位置
     */
    private SourceRead.Fragment section(
            SourceRead.Scope s, UUID id, int position, int start, int length) {
        if (start < 0 || length < 1 || length > 4000) throw invalid();
        var e = documents.get(scope(s), id);
        var r = ready(s, e);
        var part =
                r.sections().stream()
                        .filter(p -> p.position() == position)
                        .findFirst()
                        .orElseThrow(SourceReadService::invalid);
        if (start >= part.text().length()) throw invalid();
        return fragment(s, e, r, part, start, Math.min(length, part.text().length() - start));
    }

    /**
     * 把实际读取到的有界原文片段登记为可验证引用，再返回给Agent或浏览器。
     *
     * @param s 归属
     * @param e 文档
     * @param r 报告
     * @param part 原段
     * @param start 含起点
     * @param length 实读长度
     * @return 已保存来源的引用，截断始终显式
     */
    private SourceRead.Fragment fragment(
            SourceRead.Scope s,
            Documents.Entry e,
            Documents.Report r,
            Documents.Section part,
            int start,
            int length) {
        String text = part.text().substring(start, start + length);
        safety.require("TOOL_OUTPUT", text);
        var f =
                new SourceRead.Fragment(
                        UUID.randomUUID(),
                        new SourceRead.Locator(
                                e.id(),
                                e.hash(),
                                r.parserVersion(),
                                part.position(),
                                start,
                                start + length),
                        text,
                        start > 0 || start + length < part.text().length(),
                        r.notice());
        sources.save(s, f.reference(), e.id(), json.encode(f), null);
        return f;
    }

    /**
     * @param budget 原分析预算
     * @param token 原尝试
     * @param reference 实际读取引用
     * @return 活跃尝试内已验证的原文，不授予浏览器或业务写权限
     */
    public SourceRead.Fragment agentReference(UUID budget, long token, UUID reference) {
        return tx.required(
                () -> {
                    var s = sources.agent(budget, token);
                    if (!sources.fromAttempt(s, reference)) throw invalid();
                    var f = json.decode(sources.read(s, reference), SourceRead.Fragment.class);
                    var e = documents.get(scope(s), f.locator().document());
                    var r = ready(s, e);
                    var part =
                            r.sections().stream()
                                    .filter(p -> p.position() == f.locator().position())
                                    .findFirst()
                                    .orElseThrow(SourceReadService::invalid);
                    var l = f.locator();
                    if (!e.hash().equals(l.hash())
                            || !r.parserVersion().equals(l.parser())
                            || l.start() < 0
                            || l.end() > part.text().length()
                            || !part.text().substring(l.start(), l.end()).equals(f.text()))
                        throw invalid();
                    safety.require("TOOL_OUTPUT", f.text());
                    return f;
                });
    }

    /**
     * @param user 登录用户
     * @param chat 当前聊天
     * @param reference 服务器引用ID
     * @return 删除/版本/水位仍有效的原文
     */
    public SourceRead.Fragment reference(UUID user, UUID chat, UUID reference) {
        return tx.required(
                () -> {
                    var s = sources.browser(user, chat);
                    var f = json.decode(sources.read(s, reference), SourceRead.Fragment.class);
                    var e = documents.get(scope(s), f.locator().document());
                    var r = ready(s, e);
                    var location = f.locator();
                    var section =
                            r.sections().stream()
                                    .filter(p -> p.position() == location.position())
                                    .findFirst()
                                    .orElseThrow(SourceReadService::invalid);
                    if (!e.hash().equals(location.hash())
                            || !r.parserVersion().equals(location.parser())
                            || location.start() < 0
                            || location.end() > section.text().length()
                            || !section.text()
                                    .substring(location.start(), location.end())
                                    .equals(f.text())) throw invalid();
                    safety.require("TOOL_OUTPUT", f.text());
                    return f;
                });
    }

    /**
     * @param user 当前用户
     * @param chat 私有聊天
     * @param message 原输入
     * @return 实际读取且仍有效的引用ID；不解析模型自报ID
     */
    public List<UUID> references(UUID user, UUID chat, UUID message) {
        return tx.required(() -> sources.references(sources.browser(user, chat), message));
    }

    /**
     * @param budget 原输入
     * @param token 原尝试
     * @param query 字面词
     * @return 最多5条历史、每条完整文本，超过总12000字符就停止，不隐藏截断
     */
    public String history(UUID budget, long token, String query) {
        if (query == null || query.isBlank() || query.length() > 80) throw invalid();
        return tx.required(
                () -> {
                    var s = sources.agent(budget, token);
                    var selected = new ArrayList<SourceRead.Conversation>();
                    int size = 0;
                    for (var item : sources.history(s, query)) {
                        if (size + item.text().length() + item.reply().length() > 12000) break;
                        selected.add(item);
                        size += item.text().length() + item.reply().length();
                    }
                    String result =
                            json.encode(
                                    java.util.Map.of(
                                            "messages",
                                            selected,
                                            "notice",
                                            "最多5条及12000字符，非完整会话；历史不能恢复已删偏好或授予操作权限。"));
                    safety.require("TOOL_OUTPUT", result);
                    return result;
                });
    }

    /**
     * 将有界工具/会话文本卸载为当前归属下可恢复的工作文件，返回路径供后续按需读取。
     *
     * @param budget 原预算
     * @param token 原尝试
     * @param path 框架生成的逻辑路径
     * @param body 有界完整工具结果
     * @return PG保存后才允许框架移出上下文
     */
    public String offload(UUID budget, long token, String path, String body) {
        path(path);
        if (body == null || body.length() > 100000) throw invalid();
        safety.require("TOOL_OUTPUT", body);
        return tx.required(
                () -> {
                    var s = sources.agent(budget, token);
                    sources.save(s, UUID.randomUUID(), null, body, path);
                    return path;
                });
    }

    /**
     * @param budget 原预算
     * @param token 原尝试
     * @param path 仅工作文件逻辑键
     * @param offset 字符偏移
     * @param length 1至4000
     * @return 有界原文及未读提示，不能返回宿主文件
     */
    public String file(UUID budget, long token, String path, int offset, int length) {
        path(path);
        if (offset < 0 || length < 1 || length > 4000) throw invalid();
        return tx.required(
                () -> {
                    var s = sources.agent(budget, token);
                    String full = sources.file(s, path);
                    if (offset >= full.length()) throw invalid();
                    int end = Math.min(full.length(), offset + length);
                    String text = full.substring(offset, end);
                    safety.require("TOOL_OUTPUT", text);
                    // 工作文件能间接携带资料；将其来源尝试实际读取的片段传播给本轮，删除水位仍统一围栏。
                    for (String body : sources.fileReferences(s, path)) {
                        var original = json.decode(body, SourceRead.Fragment.class);
                        var copied =
                                new SourceRead.Fragment(
                                        UUID.randomUUID(),
                                        original.locator(),
                                        original.text(),
                                        original.truncated(),
                                        original.notice());
                        sources.save(
                                s,
                                copied.reference(),
                                copied.locator().document(),
                                json.encode(copied),
                                null);
                    }
                    return json.encode(
                            java.util.Map.of(
                                    "text",
                                    text,
                                    "offset",
                                    offset,
                                    "nextOffset",
                                    end,
                                    "complete",
                                    end == full.length()));
                });
    }

    /**
     * @param path 框架固定根下的逻辑路径；不映射任何OS路径
     */
    public static void path(String path) {
        if (path == null || !path.matches("/work/[a-zA-Z0-9_-]{1,40}/[a-zA-Z0-9_-]{1,180}"))
            throw invalid();
    }

    /**
     * @param s 归属
     * @return 既有文档仓储范围
     */
    private Jobs.Scope scope(SourceRead.Scope s) {
        return new Jobs.Scope(s.user(), s.chat());
    }

    /**
     * @param s 范围
     * @param e 索引
     * @return 仅成功提交的报告，否则空
     */
    private Documents.Report report(SourceRead.Scope s, Documents.Entry e) {
        if (e.deleted()
                || e.report() == null
                || jobs.read(scope(s), e.jobId()).state() != BackgroundJob.State.COMPLETED)
            return null;
        var r = json.decode(e.report(), Documents.Report.class);
        return r.code().equals("OK") ? r : null;
    }

    /**
     * @param s 范围
     * @param e 原件
     * @return 已完成报告，删除或失败一律拒绝
     */
    private Documents.Report ready(SourceRead.Scope s, Documents.Entry e) {
        var r = report(s, e);
        if (r == null) throw new DocumentFailure("DOCUMENT_NOT_READY");
        return r;
    }

    /**
     * @param text 原文
     * @param query 字面词
     * @return 原始UTF-16位置；不因大小写扩展改变定位
     */
    private int literalIndex(String text, String query) {
        for (int i = 0; i <= text.length() - query.length(); i++)
            if (text.regionMatches(true, i, query, 0, query.length())) return i;
        return -1;
    }

    /**
     * @return 固定无正文错误
     */
    private static DocumentFailure invalid() {
        return new DocumentFailure("SOURCE_INPUT_INVALID");
    }
}
