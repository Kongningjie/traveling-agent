package com.lawrence.travel.application.support;

import com.lawrence.travel.application.document.SourceReadService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.SupportStore;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.domain.support.ProblemState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 用户反馈、受限维护及问题生命周期用例；不自动改需求、记忆、权限或触发Agent。 */
public final class SupportService {
    private final SupportStore store;
    private final BusinessTransactions tx;
    private final InteractionCodec json;
    private final SafetyGate safety;
    private final SourceReadService sources;
    private final DiagnosticBuffer buffer;

    /**
     * @param store 权威端口
     * @param tx 短事务
     * @param json 规范JSON
     * @param safety 文本检查
     * @param sources 活跃原文引用
     * @param buffer 固定故障降级
     */
    public SupportService(
            SupportStore store,
            BusinessTransactions tx,
            InteractionCodec json,
            SafetyGate safety,
            SourceReadService sources,
            DiagnosticBuffer buffer) {
        this.store = store;
        this.tx = tx;
        this.json = json;
        this.safety = safety;
        this.sources = sources;
        this.buffer = buffer;
    }

    /**
     * @param user 已认证主体
     * @param in 用户主动反馈
     * @return 提交后才声明saved；重试保留原ID
     */
    public Support.Receipt create(UUID user, Support.Input in) {
        if (in == null
                || in.id() == null
                || in.target() == null
                || in.target().kind() == null
                || in.target().scope() == null
                || in.target().id() == null
                || in.category() == null
                || in.description() == null
                || in.description().length() > 1000
                || in.consent() != (in.reference() != null)) throw bad();
        safety.require("USER_INPUT", in.description());
        return run(
                user,
                () -> {
                    store.lock(user);
                    String hash = hash(json.encode(in));
                    var old = store.replay(user, in.id(), hash);
                    if (old != null) return old;
                    // 先锁用户并检查原请求，再读取目标；客户端没有提供trace/span或跨用户读取权。
                    // 可选材料许可仅对应一个当前仍有效的引用，不把整段回复或文件复制到诊断。
                    var link = store.locate(user, in.target());
                    if (in.reference() != null) {
                        if (link.chat() == null) throw bad();
                        var ref = sources.reference(user, link.chat(), in.reference());
                        if (in.target().kind() == Support.Kind.REPLY
                                && !sources.references(user, link.chat(), in.target().id())
                                        .contains(in.reference())) throw bad();
                        if (link.document() != null
                                && !link.document().equals(ref.locator().document())) throw bad();
                    }
                    return store.insert(user, in, hash, link);
                });
    }

    /**
     * @param user 本人
     * @param offset 分页
     * @return 私有反馈摘要
     */
    public List<Support.Feedback> list(UUID user, int offset) {
        page(offset);
        return run(user, () -> store.feedback(user, offset));
    }

    /**
     * @param user 操作者
     * @param id 反馈
     * @param maintenance 敏感维护读取
     * @return 用户填写的正文及可选引用，不复制原文
     */
    public Support.Detail detail(UUID user, UUID id, boolean maintenance) {
        return run(
                user,
                () -> {
                    var result = store.detail(user, id, maintenance);
                    safety.require("USER_OUTPUT", result.description());
                    return result;
                });
    }

    /**
     * @param user 操作者
     * @return 后端实时权限，不授予新角色
     */
    public Map<String, Boolean> access(UUID user) {
        return run(
                user,
                () ->
                        Map.of(
                                "maintenance",
                                store.allowed(user, false),
                                "sensitive",
                                store.allowed(user, true)));
    }

    /**
     * @param user 维护者
     * @param offset 分页
     * @return 固定聚合元数据
     */
    public List<Support.Group> groups(UUID user, int offset) {
        page(offset);
        return run(user, () -> store.groups(user, offset));
    }

    /**
     * @param user 维护者
     * @param id 聚合或用户问题编号
     * @return 当前组
     */
    public Support.Group group(UUID user, UUID id) {
        return run(user, () -> store.group(user, id));
    }

    /**
     * @param user 维护者
     * @param id 聚合或问题编号
     * @return 至多100个实际问题及span
     */
    public List<Support.Event> events(UUID user, UUID id) {
        return run(user, () -> store.events(user, id));
    }

    /**
     * @param user 维护者
     * @param trace 原始链路
     * @return 至多200个固定步骤
     */
    public Support.Trace trace(UUID user, UUID trace) {
        return run(user, () -> store.trace(user, trace));
    }

    /**
     * @param user 维护者
     * @param id 问题组
     * @param c 显式期望版本及修复凭据
     * @return 已提交状态；重试执行并不等于已修复
     */
    public Support.Group change(UUID user, UUID id, Support.Change c) {
        if (c == null
                || c.next() == null
                || c.revision() < 0
                || c.cause() == null
                || c.repair() == null
                || !Set.of(
                                "UNKNOWN",
                                "CODE",
                                "CONFIG",
                                "PROVIDER",
                                "INPUT",
                                "CONTEXT",
                                "DATA",
                                "USER")
                        .contains(c.cause())
                || !Set.of("", "CODE", "CONFIG", "PROVIDER", "CLARIFICATION").contains(c.repair())
                || !text(c.version())
                || !text(c.regression())) throw bad();
        safety.require("USER_INPUT", json.encode(c));
        return run(
                user,
                () -> {
                    var g = store.group(user, id);
                    if (g.revision() != c.revision()) throw new SupportFailure("SUPPORT_CONFLICT");
                    if (!g.state().permits(c.next(), c.version(), c.regression(), c.passed())
                            || ((c.next() == ProblemState.RESOLVED
                                            || c.next() == ProblemState.VERIFIED)
                                    && c.repair().isEmpty())) throw bad();
                    if (c.next() == ProblemState.DUPLICATE) {
                        if (c.duplicateOf() == null || c.duplicateOf().equals(g.id())) throw bad();
                        var target = store.group(user, c.duplicateOf());
                        if (target.state() == ProblemState.DUPLICATE
                                || !target.id().equals(c.duplicateOf())) throw bad();
                    } else if (c.duplicateOf() != null) throw bad();
                    store.change(user, g.id(), c);
                    return store.group(user, g.id());
                });
    }

    /**
     * @param user 登录主体
     * @param id 原请求
     * @param code 固定故障
     * @param component 固定位置
     * @return 仅成功提交后返回真实问题编号
     */
    public UUID client(UUID user, UUID id, String code, String component) {
        if (id == null
                || code == null
                || component == null
                || !Set.of("RENDER", "RESOURCE", "NETWORK", "PROTOCOL").contains(code)
                || !Set.of("APP", "CHAT", "DOCUMENT", "ADVICE").contains(component)) throw bad();
        return run(user, () -> store.client(user, id, code, component));
    }

    /**
     * @param user 维护者
     * @return 固定降级计数，权限不在内存放行
     */
    public Support.Health health(UUID user) {
        return run(
                user,
                () -> {
                    if (!store.allowed(user, false)) throw new SupportFailure("SUPPORT_FORBIDDEN");
                    var h = buffer.health();
                    return new Support.Health(
                            h.rows(), h.dropped() + store.dropped(), h.failures());
                });
    }

    /**
     * @param user 失败归属
     * @param action 同一业务短事务
     * @param <T> 结果
     * @return 提交后结果；底层异常不回显、不创建成功票据
     */
    private <T> T run(UUID user, Supplier<T> action) {
        try {
            return tx.required(action);
        } catch (SupportFailure expected) {
            if (expected.getMessage().equals("SUPPORT_CAPACITY")) buffer.capacityRejected();
            throw expected;
        } catch (com.lawrence.travel.application.document.DocumentFailure expected) {
            throw bad();
        } catch (com.lawrence.travel.application.travel.TravelStorageFailure expected) {
            if (expected.code()
                    != com.lawrence.travel.application.travel.TravelStorageFailure.Code
                            .DEPENDENCY_UNAVAILABLE) throw new SupportFailure("SUPPORT_NOT_FOUND");
            buffer.failed(user);
            throw new SupportFailure("SUPPORT_UNAVAILABLE");
        } catch (SafetyGate.Failure expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            buffer.failed(user);
            throw new SupportFailure("SUPPORT_UNAVAILABLE");
        }
    }

    /**
     * @param offset 分页偏移，避免不受限扫描
     */
    private void page(int offset) {
        if (offset < 0 || offset > 10000) throw bad();
    }

    /**
     * @param value 可空长度受限的固定修复标识
     * @return 不接受自由正文或凭据
     */
    private boolean text(String value) {
        return value != null && (value.isEmpty() || ProblemState.token(value));
    }

    /**
     * @param value 规范输入
     * @return 幂等摘要，不保存另一份正文
     */
    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    /**
     * @return 固定错误
     */
    private SupportFailure bad() {
        return new SupportFailure("SUPPORT_INPUT_INVALID");
    }
}
