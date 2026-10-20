package com.lawrence.travel.infrastructure.lifecycle;

import com.lawrence.travel.application.lifecycle.Lifecycle;
import com.lawrence.travel.application.lifecycle.LifecycleFailure;
import com.lawrence.travel.application.port.DeletionLedger;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** 独立最小删除清单，不放入业务备份；PG序列锁串行写入，条目落盘后原子推进头。 */
public final class FileDeletionLedger implements DeletionLedger {
    private final Path root;

    /**
     * @param root 独立受限目录；拒绝符号链接，不接受用户路径
     */
    public FileDeletionLedger(Path root) {
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw fail();
            this.root = root.toRealPath();
        } catch (java.io.IOException e) {
            throw fail();
        }
    }

    /** {@inheritDoc} 校验最新条目，并补封已完整落盘但尚未更新头的连续条目；PG随后重放，缺头不能猜测重建。 */
    @Override
    public synchronized Lifecycle.Head head() {
        var file = path("head");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        var p = text(file).split("\t", -1);
        try {
            if (p.length != 4 || !p[0].equals("v1") || !p[3].matches("[a-f0-9]{64}")) throw fail();
            long n = Long.parseLong(p[2]);
            if (n < 0 || n > 1000000) throw fail();
            var current=new Lifecycle.Head(UUID.fromString(p[1]), n, p[3]);
            if(n>0 && !hash(n).equals(current.hash())) throw fail();
            // 条目已fsync但头未改名时也必须完成原删除意图；不能让下一请求的时间戳/UUID覆盖孤立条目。
            while(current.sequence()<1000000 && Files.exists(path(name(current.sequence()+1)),LinkOption.NOFOLLOW_LINKS)){
                long next=current.sequence()+1;
                read(next,current.hash());
                current=new Lifecycle.Head(current.instance(),next,hash(next));
            }
            if(current.sequence()!=n) write("head",encode(current),true);
            return current;
        } catch (IllegalArgumentException e) {
            throw fail();
        }
    }

    /** {@inheritDoc} 不覆盖已存在的独立头；首次记录不含用户数据。 */
    @Override
    public synchronized Lifecycle.Head initialize(UUID instance) {
        if (head() != null) throw fail();
        try(var entries=Files.list(root)){
            if(entries.anyMatch(p->p.getFileName().toString().endsWith(".del"))) throw fail();
        }catch(java.io.IOException unavailable){throw fail();}
        var h = new Lifecycle.Head(instance, 0, "0".repeat(64));
        write("head", encode(h), true);
        return h;
    }

    /** {@inheritDoc} 单条至多1024字节，全部文本均为技术ID/时间；最多100万条活跃记录后拒绝新增，不无限写盘。 */
    @Override
    public synchronized Lifecycle.Head append(Lifecycle.Head expected, Lifecycle.Entry e) {
        if (!expected.equals(head()) || e.sequence() != expected.sequence() + 1) throw fail();
        // 清单仅追加、从不在线裁剪；单调序列即可限制总量，避免每次删除扫描全部历史文件。
        // 最后一条写入后头尚未推进时允许同条目重试，不能因孤立条目已存在而卡死恢复。
        if (e.sequence() < 1 || e.sequence() > 1000000) throw fail();
        String body =
                e.sequence()
                        + "\t"
                        + e.id()
                        + "\t"
                        + e.user()
                        + "\t"
                        + e.kind()
                        + "\t"
                        + e.target()
                        + "\t"
                        + id(e.chat())
                        + "\t"
                        + id(e.task())
                        + "\t"
                        + e.deletedAt().toString();
        String hash = digest(expected.hash() + "\n" + body);
        String record = expected.hash() + "\n" + body + "\n" + hash;
        String key = name(e.sequence());
        var existing = path(key);
        if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            if (!text(existing).equals(record)) throw fail();
        } else write(key, record, false);
        var next = new Lifecycle.Head(expected.instance(), e.sequence(), hash);
        write("head", encode(next), true);
        return next;
    }

    /** {@inheritDoc} 只读取头所指连续序列，不接受任意文件名。 */
    @Override
    public synchronized Lifecycle.Entry read(long seq, String previous) {
        var lines = record(seq);
        if (!lines[0].equals(previous)) throw fail();
        var p = lines[1].split("\t", -1);
        try {
            if (p.length != 8 || Long.parseLong(p[0]) != seq) throw fail();
            return new Lifecycle.Entry(
                    seq,
                    UUID.fromString(p[1]),
                    UUID.fromString(p[2]),
                    Lifecycle.Kind.valueOf(p[3]),
                    UUID.fromString(p[4]),
                    optional(p[5]),
                    optional(p[6]),
                    java.time.Instant.parse(p[7]));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw fail();
        }
    }

    /** {@inheritDoc} 单条链哈希也重新校验，不信任独立头中的任意文本。 */
    @Override
    public synchronized String hash(long seq) {
        return record(seq)[2];
    }

    /**
     * @param seq 可信顺序
     * @return 验证过的三行记录
     */
    private String[] record(long seq) {
        if (seq < 1) throw fail();
        var p = text(path(name(seq))).split("\n", -1);
        if (p.length != 3
                || !p[0].matches("[a-f0-9]{64}")
                || !p[2].equals(digest(p[0] + "\n" + p[1]))) throw fail();
        return p;
    }

    /**
     * @param seq 可信顺序
     * @return 单层固定文件名
     */
    private String name(long seq) {
        return String.format(java.util.Locale.ROOT, "%020d.del", seq);
    }

    /**
     * @param h 受控头
     * @return 固定格式
     */
    private String encode(Lifecycle.Head h) {
        return "v1\t" + h.instance() + "\t" + h.sequence() + "\t" + h.hash();
    }

    /**
     * @param value UUID可空
     * @return 明确空值
     */
    private String id(UUID value) {
        return value == null ? "-" : value.toString();
    }

    /**
     * @param value 固定字段
     * @return 可空UUID
     */
    private UUID optional(String value) {
        return value.equals("-") ? null : UUID.fromString(value);
    }

    /**
     * @param value 有界技术记录
     * @return SHA256
     */
    private String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw fail();
        }
    }

    /**
     * @param key 仅内部固定单层键
     * @return 拒绝链接的受管路径
     */
    private Path path(String key) {
        var p = root.resolve(key);
        if (!p.getParent().equals(root) || Files.isSymbolicLink(p)) throw fail();
        return p;
    }

    /**
     * @param file 已检查路径
     * @return 最多1024字节，拒绝空文件及损坏格式
     */
    private String text(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024)
                throw fail();
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw fail();
        }
    }

    /**
     * @param key 固定键
     * @param value 有界技术记录
     * @param replace 是否只允许更新头
     */
    private void write(String key, String value, boolean replace) {
        var temp = path(key + ".pending");
        try {
            try (var out =
                    FileChannel.open(
                            temp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                var bytes = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            if (replace)
                Files.move(
                        temp,
                        path(key),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temp, path(key), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            throw fail();
        }
    }

    /**
     * @return 无真实路径或原始异常的失败
     */
    private LifecycleFailure fail() {
        return new LifecycleFailure("DELETION_LEDGER_UNAVAILABLE");
    }
}
