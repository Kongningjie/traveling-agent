package com.lawrence.travel.application.lifecycle;

import com.lawrence.travel.application.execution.Jobs;
import com.lawrence.travel.application.port.ArtifactFiles;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DeletionLedger;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.JobStore;
import com.lawrence.travel.application.port.LifecycleStore;
import com.lawrence.travel.domain.travel.TaskScope;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** 删除先封存独立清单，再原子撤权/清正文；恢复屏障在最新清单全部重放后才开放。 */
public final class LifecycleService {
    private final LifecycleStore store;
    private final BusinessTransactions tx;
    private final DeletionLedger ledger;
    private final ArtifactFiles documents;
    private final ArtifactFiles artifacts;
    private final JobStore jobs;
    private final ExecutionEngineStore engine;
    private final Clock clock;
    private volatile Lifecycle.Head readyHead;

    /**
     * @param store PG权威
     * @param tx 同一短事务
     * @param ledger 独立删除清单
     * @param documents 原件卷
     * @param artifacts 必要文件卷
     * @param jobs 既有取消/计量
     * @param engine 既有旅行取消
     * @param clock 受控UTC时钟
     */
    public LifecycleService(
            LifecycleStore store,
            BusinessTransactions tx,
            DeletionLedger ledger,
            ArtifactFiles documents,
            ArtifactFiles artifacts,
            JobStore jobs,
            ExecutionEngineStore engine,
            Clock clock) {
        this.store = store;
        this.tx = tx;
        this.ledger = ledger;
        this.documents = documents;
        this.artifacts = artifacts;
        this.jobs = jobs;
        this.engine = engine;
        this.clock = clock;
    }

    /**
     * @return PG与独立最新清单同时满足才允许私有访问/执行；恢复旧库、丢头或损坏均关闭
     */
    public boolean ready() {
        var expected = readyHead;
        if (expected == null) return false;
        try {
            var current = ledger.head();
            // 同一部署内其他受控实例/测试上下文可以推进已提交水位；只接受单调前进且双端一致。
            // 文件已推进而PG未提交、恢复旧库或更换部署身份时仍失败关闭。
            if (current == null
                    || !current.instance().equals(expected.instance())
                    || current.sequence() < expected.sequence()
                    || !current.equals(store.status())) return false;
            readyHead = current;
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** 启动与故障后恢复复用同一重放；清单缺失/回退/链损坏拒绝开放，不能重新初始化旧部署。 */
    public void recover() {
        readyHead = null;
        readyHead =
                tx.required(
                        () -> {
                            var db = store.head();
                            var disk = ledger.head();
                            if (disk == null) {
                                if (store.initialized())
                                    throw new LifecycleFailure("LATEST_DELETIONS_MISSING");
                                disk = ledger.initialize(db.instance());
                            }
                            if (!db.instance().equals(disk.instance())
                                    || disk.sequence() < db.sequence())
                                throw new LifecycleFailure("LATEST_DELETIONS_MISMATCH");
                            if (disk.sequence() > 0
                                    && !ledger.hash(disk.sequence()).equals(disk.hash()))
                                throw new LifecycleFailure("LATEST_DELETIONS_MISMATCH");
                            String previous = db.hash();
                            for (long n = db.sequence() + 1; n <= disk.sequence(); n++) {
                                var e = ledger.read(n, previous);
                                store.apply(e);
                                previous = ledger.hash(n);
                            }
                            if (!previous.equals(disk.hash()))
                                throw new LifecycleFailure("LATEST_DELETIONS_MISMATCH");
                            store.advance(disk);
                            return disk;
                        });
    }

    /**
     * @param user 当前用户
     * @param kind 删除范围
     * @param target 私有对象
     * @return 当前影响清单，展示前不得执行删除
     */
    public Lifecycle.Preview preview(UUID user, Lifecycle.Kind kind, UUID target) {
        requireReady();
        if (kind == null || target == null) throw invalid();
        return tx.required(() -> store.preview(user, kind, target));
    }

    /**
     * @param user 当前用户
     * @param request 稳定幂等ID
     * @param kind 明确范围
     * @param target 私有对象
     * @param revision 用户实际看到的对象集摘要
     * @param confirmed 明确确认
     * @return 已提交撤权，不虚称原件已经清完
     */
    public Lifecycle.Receipt delete(
            UUID user,
            UUID request,
            Lifecycle.Kind kind,
            UUID target,
            String revision,
            boolean confirmed) {
        if (request == null || kind == null || target == null || revision == null || !confirmed)
            throw invalid();
        requireReady();
        try {
            var result =
                    tx.required(
                            () -> {
                                var old = store.replay(user, request);
                                if (old != null) {
                                    if (old.kind() != kind || !old.target().equals(target))
                                        throw new LifecycleFailure("DELETE_CONFLICT");
                                    return new Lifecycle.Receipt(
                                            true, request, false, store.pending(old));
                                }
                                var preview = store.preview(user, kind, target);
                                if (!preview.revision().equals(revision))
                                    throw new LifecycleFailure("DELETE_SCOPE_CHANGED");
                                var head = store.head();
                                if (!head.equals(ledger.head()))
                                    throw new LifecycleFailure("RESTORE_REQUIRED");
                                // 两用户可能提交相同客户端UUID；全局序列锁内先拒绝碰撞，不能把他人的墓碑当作成功重放。
                                // 这个校验必须早于独立清单写入，否则恢复可能被永久的唯一冲突阻断。
                                if (store.requestUsed(request))
                                    throw new LifecycleFailure("DELETE_CONFLICT");
                                var entry =
                                        new Lifecycle.Entry(
                                                head.sequence() + 1,
                                                request,
                                                user,
                                                kind,
                                                target,
                                                preview.chat(),
                                                preview.task(),
                                                clock.instant());
                                // 清单先fsync，即使随后PG事务失败，也只会保守删除用户已明确要求删除的对象；下次恢复补齐PG。
                                // 运行时屏障比较PG/文件头，阻止“清单已前进但PG未提交”期间继续读取旧资料。
                                var next = ledger.append(head, entry);
                                if (preview.chat() != null) {
                                    var scope = new Jobs.Scope(user, preview.chat());
                                    if (kind == Lifecycle.Kind.DOCUMENT) {
                                        // 来源版本只撤销读者与本文件解析；无关解析使用独立原件，不随之丢失资格。
                                        for (var job :
                                                store.sourceJobs(user, preview.chat(), target))
                                            jobs.control(scope, job, false);
                                    } else jobs.invalidate(scope);
                                }
                                if (preview.task() != null)
                                    for (var run : store.runs(user, preview.task()))
                                        engine.cancel(new TaskScope(user, preview.task()), run);
                                store.apply(entry);
                                store.advance(next);
                                return new Lifecycle.Receipt(
                                        true, request, false, store.pending(entry));
                            });
            readyHead = store.status();
            return result;
        } catch (RuntimeException failure) {
            try {
                if (!Objects.equals(readyHead, ledger.head())) readyHead = null;
            } catch (RuntimeException unavailable) {
                readyHead = null;
            }
            throw failure;
        }
    }

    /**
     * @param user 当前用户
     * @param chat 旧显式附件DELETE入口
     * @param document 原件；生产装配仍走同一清单与撤权用例
     */
    public void deleteDocument(UUID user, UUID chat, UUID document) {
        var request =
                UUID.nameUUIDFromBytes(
                        ("document-delete/" + user + "/" + document)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var old = tx.required(() -> store.replay(user, request));
        if (old != null) return;
        var p = preview(user, Lifecycle.Kind.DOCUMENT, document);
        if (!chat.equals(p.chat())) throw new LifecycleFailure("DELETE_NOT_FOUND");
        delete(user, request, Lifecycle.Kind.DOCUMENT, document, p.revision(), true);
    }

    /** 有界周期：先恢复未提交墓碑，再逐原件清理；单项失败不阻塞其他删除，失败重试/24小时逾期可观察。 */
    public void maintain() {
        if (!ready()) recover();
        for (var file : store.files(clock.instant())) {
            try {
                (file.document() ? documents : artifacts).delete(file.key());
                tx.required(
                        () -> {
                            store.purged(file.id());
                            return null;
                        });
            } catch (RuntimeException failed) {
                tx.required(
                        () -> {
                            store.failed(file, clock.instant());
                            return null;
                        });
            }
        }
        tx.required(() -> store.prune(clock.instant()));
    }

    /**
     * @return 最小清理指标；读取调用方仍须维护权限，不包含用户正文
     */
    public Lifecycle.Health health() {
        return tx.required(() -> store.health(clock.instant()));
    }

    /** 恢复未完成时失败关闭；停止入口仍可通过既有接口直接执行。 */
    private void requireReady() {
        if (!ready()) throw new LifecycleFailure("RESTORE_REQUIRED");
    }

    /**
     * @return 固定输入错误
     */
    private LifecycleFailure invalid() {
        return new LifecycleFailure("DELETE_INPUT_INVALID");
    }
}
