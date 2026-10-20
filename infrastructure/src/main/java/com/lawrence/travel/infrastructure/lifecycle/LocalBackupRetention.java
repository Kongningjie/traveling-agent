package com.lawrence.travel.infrastructure.lifecycle;

import com.lawrence.travel.application.lifecycle.LifecycleFailure;
import com.lawrence.travel.application.port.InteractionCodec;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/** 仅清理指定独立根下带v3受管标记的过期备份；拒绝链接、未知文件和越界路径。 */
public final class LocalBackupRetention {
    private final Path root;
    private final InteractionCodec json;

    /**
     * @param root 服务端明确受管备份根
     * @param json 有界manifest解析
     */
    public LocalBackupRetention(Path root, InteractionCodec json) {
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw fail();
            this.root = root.toRealPath();
            this.json = json;
        } catch (java.io.IOException e) {
            throw fail();
        }
    }

    /**
     * @param now 受控UTC时间
     * @return 本次清理备份数，未知格式不擅自删除
     */
    public int prune(Instant now) {
        int count = 0;
        try (var directories = Files.list(root)) {
            for (var dir : directories.limit(10000).toList()) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir))
                    continue;
                var manifest = dir.resolve("manifest.json");
                if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(manifest)
                        || Files.size(manifest) > 1048576) continue;
                var data = json.decode(Files.readString(manifest), Map.class);
                if (!"traveling-agent-v3".equals(data.get("managedBackup"))) continue;
                Instant created = Instant.parse(data.get("createdAt").toString()),
                        expires = Instant.parse(data.get("expiresAt").toString());
                if (expires.isAfter(created.plusSeconds(30L * 86400)) || expires.isBefore(created))
                    throw fail();
                if (expires.isAfter(now)) continue;
                var safe = dir.toRealPath();
                if (!safe.getParent().equals(root) || Files.isSymbolicLink(dir)) throw fail();
                try (var stream = Files.list(safe)) {
                    var files = stream.toList();
                    for (var file : files)
                        if (!file.getParent().equals(safe)
                                || Files.isSymbolicLink(file)
                                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                || !java.util.Set.of(
                                                "postgres.dump",
                                                "files.tar",
                                                "documents.tar",
                                                "manifest.json")
                                        .contains(file.getFileName().toString())) throw fail();
                    // 清单最后删除：中途IO失败时，下轮仍能识别受管备份并重试，不能遗留无人回收的私有副本。
                    for (var file : files.stream().filter(f -> !f.equals(manifest)).toList())
                        Files.delete(file);
                    Files.delete(manifest);
                }
                Files.delete(safe);
                count++;
            }
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw fail();
        }
        return count;
    }

    /**
     * @return 不回显真实备份路径或异常正文
     */
    private LifecycleFailure fail() {
        return new LifecycleFailure("BACKUP_RETENTION_FAILED");
    }
}
