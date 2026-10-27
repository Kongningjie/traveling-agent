package com.lawrence.travel.infrastructure.artifact;

import com.lawrence.travel.application.artifact.ArtifactFailure;
import com.lawrence.travel.application.artifact.ArtifactService;
import com.lawrence.travel.application.port.ArtifactFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/** 必要文件卷；严格键、拒绝链接、完整落盘后原子改名，不读取可丢弃的Harness工作区。 */
public final class VolumeArtifactFiles implements ArtifactFiles {
    private static final String UUID_PART =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern KEY =
            Pattern.compile(
                    "(?:documents/"
                            + UUID_PART
                            + "/"
                            + UUID_PART
                            + "/"
                            + UUID_PART
                            + "\\.bin|users/"
                            + UUID_PART
                            + "/tasks/"
                            + UUID_PART
                            + "/runs/"
                            + UUID_PART
                            + "/"
                            + UUID_PART
                            + "\\.bin)");
    private final Path root;
    private volatile boolean diskStopped;

    /** {@inheritDoc} 墓碑提交后才允许清理，路径检查与读取完全相同。 */
    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
            throw failure();
        }
    }

    /**
     * @param root 独立受限文件根；配置受信，目录不得由模型或用户写入
     */
    public VolumeArtifactFiles(Path root) {
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException ignored) {
            throw failure();
        }
    }

    /** {@inheritDoc} 不支持原子改名的文件系统明确失败，不降级成可见半文件。 */
    @Override
    public void write(String key, byte[] bytes) {
        if (bytes == null || bytes.length > ArtifactService.MAX_BYTES) throw failure();
        try {
            var volume = Files.getFileStore(root);
            double used = 1.0 - (double) volume.getUsableSpace() / volume.getTotalSpace();
            if (used >= 0.85) diskStopped = true;
            else if (used <= 0.80) diskStopped = false;
            if (diskStopped) throw failure();
        } catch (IOException ignored) {
            throw failure();
        }
        Path target = resolve(key);
        Path temporary = target.resolveSibling(target.getFileName() + ".pending");
        try {
            Files.createDirectories(target.getParent());
            resolve(key);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw failure();
            // 上一次进程可在原子改名前终止；调用方持登记锁及私有业务锁，同键无并行写者。
            // 仅清除本次受管键的非链接临时文件，不把半文件提升为可见原件。
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(temporary)
                        || !temporary.toRealPath().startsWith(root)) throw failure();
                Files.delete(temporary);
            }
            try (var channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            throw failure();
        }
    }

    /** {@inheritDoc} 在读取前检查大小，并通过有界流避免文件并发增长导致无界分配。 */
    @Override
    public byte[] read(String key) {
        Path file = resolve(key);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            throw new ArtifactFailure("ARTIFACT_MISSING");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > ArtifactService.MAX_BYTES)
                throw new ArtifactFailure("ARTIFACT_CORRUPT");
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(ArtifactService.MAX_BYTES + 1);
                if (bytes.length > ArtifactService.MAX_BYTES)
                    throw new ArtifactFailure("ARTIFACT_CORRUPT");
                return bytes;
            }
        } catch (IOException ignored) {
            throw failure();
        }
    }

    /** {@inheritDoc} 仅回收本存储生成的老孤儿和未提交临时文件；符号链接、未知文件一律保留。 */
    @Override
    public int collect(Set<String> references, Instant before) {
        int count = 0;
        try (var paths = Files.walk(root, 9)) {
            var iterator =
                    paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String key = root.relativize(path).toString().replace('\\', '/');
                String finalKey =
                        key.endsWith(".pending") ? key.substring(0, key.length() - 8) : key;
                if (!KEY.matcher(finalKey).matches() || references.contains(finalKey)) continue;
                resolve(finalKey);
                if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                        .toInstant()
                        .isBefore(before)) {
                    Files.delete(path);
                    count++;
                }
            }
            return count;
        } catch (IOException ignored) {
            throw failure();
        }
    }

    /**
     * @param key 严格服务端键
     * @return 根内路径；拒绝每个既有路径组件的链接和Windows目录联接跳出
     */
    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw failure();
        Path target = root.resolve(key).normalize();
        try {
            for (Path current = target;
                    current != null && !current.equals(root);
                    current = current.getParent()) {
                if (Files.isSymbolicLink(current)
                        || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                                && !current.toRealPath().startsWith(root))) throw failure();
            }
            return target;
        } catch (IOException ignored) {
            throw failure();
        }
    }

    /**
     * @return 不泄露底层路径的固定失败
     */
    private static ArtifactFailure failure() {
        return new ArtifactFailure("ARTIFACT_UNAVAILABLE");
    }
}
