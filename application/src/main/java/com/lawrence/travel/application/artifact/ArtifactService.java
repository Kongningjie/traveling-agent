package com.lawrence.travel.application.artifact;

import com.lawrence.travel.application.port.ArtifactFiles;
import com.lawrence.travel.application.port.ArtifactStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** 必要文件与数据库正文用例；不提供上传接口，也不改变阶段完成和产物选用事务。 */
public final class ArtifactService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private final ArtifactStore store;
    private final ArtifactFiles files;
    private final BusinessTransactions transactions;
    private final Clock clock;
    /** @param store 私有索引 @param files 原子文件端口 @param transactions 短事务 @param clock 保留期时钟 */
    public ArtifactService(ArtifactStore store, ArtifactFiles files, BusinessTransactions transactions, Clock clock) {
        this.store = store; this.files = files; this.transactions = transactions; this.clock = clock;
    }
    /** @param scope 后台受信归属 @param run 来源运行 @param body 已清洗的展示文本或JSON @param json JSON标记；数据库约束验证语法 @return 不可变数据库产物身份，不依赖工作区 */
    public UUID text(TaskScope scope, UUID run, String body, boolean json) {
        if (body == null) throw invalid();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); bounded(bytes); UUID id = UUID.randomUUID();
        return transactions.required(() -> { store.requireRun(scope, run); store.insert(new ArtifactStore.Entry(id, scope, run, json ? "DB_JSON" : "DB_TEXT", json ? "application/json" : "text/plain", hash(bytes), bytes.length, null, body, true)); return id; });
    }
    /** @param scope 受信归属 @param run 来源运行 @param bytes 已清洗附件或必要框架文件 @param media 白名单；框架文件仅application/octet-stream @param visible 用户附件可见，框架文件不可见 @return 原子落盘后事务登记的身份；事务失败可能留下待回收孤儿 */
    public UUID file(TaskScope scope, UUID run, byte[] bytes, String media, boolean visible) {
        bounded(bytes);
        if (media == null || !(visible ? Set.of("image/png", "image/jpeg", "application/pdf").contains(media) : "application/octet-stream".equals(media))) throw invalid();
        // 防御复制避免调用方在哈希与落盘之间修改字节；文件I/O有10MiB硬上限。
        byte[] stable = bytes.clone(); UUID id = UUID.randomUUID();
        String key = "users/" + scope.userId() + "/tasks/" + scope.taskId() + "/runs/" + run + "/" + id + ".bin";
        return transactions.required(() -> {
            store.requireRun(scope, run); store.lockFiles(false);
            files.write(key, stable);
            store.insert(new ArtifactStore.Entry(id, scope, run, "FILE", media, hash(stable), stable.length, key, null, visible)); return id;
        });
    }
    /** @param mediaType 安全响应类型 @param bytes 完整且已核对哈希的正文 */
    public record Content(String mediaType, byte[] bytes) { }
    /** @param actor 认证身份 @param id 私有产物身份 @return 有界正文；不暴露存储键或框架内部文件 */
    public Content read(UserIdentity actor, UUID id) {
        var entry = store.visible(actor.id(), id);
        byte[] bytes = "FILE".equals(entry.kind()) ? files.read(entry.fileKey()) : entry.body().getBytes(StandardCharsets.UTF_8);
        if (bytes.length != entry.size() || !hash(bytes).equals(entry.hash())) throw new ArtifactFailure("ARTIFACT_CORRUPT");
        return new Content(entry.mediaType(), bytes);
    }
    /** @param before 显式回收截止时间，至少保留24小时 @return 删除孤儿数量；后台维护专用，无HTTP入口 */
    public int collect(Instant before) {
        if (before == null || before.isAfter(clock.instant().minus(Duration.ofHours(24)))) throw invalid();
        // 与全部写入事务共用数据库锁，回收快照不会漏掉落盘后尚未提交的有效引用。
        return transactions.required(() -> { store.lockFiles(true); return files.collect(store.referencedFiles(), before); });
    }
    /** @param bytes 正文字节；拒绝空指针和超限，不依据阈值迁移数据库正文 */
    private static void bounded(byte[] bytes) { if (bytes == null || bytes.length > MAX_BYTES) throw invalid(); }
    /** @return 固定输入分类，不携带正文 */
    private static TravelStorageFailure invalid() { return new TravelStorageFailure(TravelStorageFailure.Code.INVALID_INPUT); }
    /** @param bytes 正文 @return 小写SHA256；不依赖JSONB序列化格式 */
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ignored) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
}
