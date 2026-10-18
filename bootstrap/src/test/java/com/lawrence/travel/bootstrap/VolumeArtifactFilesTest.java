package com.lawrence.travel.bootstrap;

import com.lawrence.travel.infrastructure.artifact.VolumeArtifactFiles;

import com.lawrence.travel.application.artifact.ArtifactFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实临时文件系统；禁止路径穿越、未知文件回收和覆盖，损坏检测由应用层完成。 */
class VolumeArtifactFilesTest {
    @TempDir Path root;
    /** @return 严格服务端生成键 */
    private String key() { return "users/" + UUID.randomUUID() + "/tasks/" + UUID.randomUUID() + "/runs/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".bin"; }
    /** 原子写入可读，重复键不覆盖原始附件，目录之外的键和链接被拒绝。 */
    @Test void atomicWriteRejectsOverwriteAndTraversal() throws Exception {
        var files = new VolumeArtifactFiles(root); String key = key(); files.write(key, new byte[]{1, 2, 3});
        assertThat(files.read(key)).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> files.write(key, new byte[]{9})).isInstanceOf(ArtifactFailure.class);
        assertThatThrownBy(() -> files.read("../outside")).isInstanceOf(ArtifactFailure.class);
        assertThatThrownBy(() -> files.write("/absolute", new byte[]{1})).isInstanceOf(ArtifactFailure.class);
        assertThat(files.read(key)).containsExactly(1, 2, 3);
        try (var paths = Files.walk(root)) { assertThat(paths.filter(p -> p.toString().endsWith(".pending")).count()).isZero(); }
    }
    /** 仅回收过期无引用文件和临时文件；有效引用、新文件及未知文件保留。 */
    @Test void collectorPreservesReferencesYoungAndUnknownFiles() throws Exception {
        var files = new VolumeArtifactFiles(root); String retained = key(), orphan = key(), recent = key(), pending = key();
        files.write(retained, new byte[]{1}); files.write(orphan, new byte[]{2}); files.write(recent, new byte[]{3});
        Path temporary = root.resolve(pending + ".pending"); Files.createDirectories(temporary.getParent()); Files.write(temporary, new byte[]{4});
        Instant old = Instant.now().minusSeconds(200000); Files.setLastModifiedTime(root.resolve(retained), FileTime.from(old)); Files.setLastModifiedTime(root.resolve(orphan), FileTime.from(old)); Files.setLastModifiedTime(temporary, FileTime.from(old)); Files.writeString(root.resolve("unknown.txt"), "保留未知文件");
        assertThat(files.collect(Set.of(retained), Instant.now().minusSeconds(86400))).isEqualTo(2);
        assertThat(files.read(retained)).containsExactly(1); assertThat(files.read(recent)).containsExactly(3); assertThat(root.resolve("unknown.txt")).exists();
        assertThatThrownBy(() -> files.read(orphan)).hasMessage("ARTIFACT_MISSING");
    }
    /** Windows缺少链接权限时只跳过链接创建部分；支持时必须拒绝目录符号链接。 */
    @Test void linkedParentCannotEscapeRoot() throws Exception {
        Path external = Files.createDirectory(root.resolve("external")); Path storage = Files.createDirectory(root.resolve("storage"));
        try { Files.createSymbolicLink(storage.resolve("users"), external); }
        catch (java.nio.file.FileSystemException | UnsupportedOperationException ignored) { org.junit.jupiter.api.Assumptions.abort("本机无符号链接创建权限"); }
        var files = new VolumeArtifactFiles(storage); assertThatThrownBy(() -> files.write(key(), new byte[]{1})).hasMessage("ARTIFACT_UNAVAILABLE");
    }
}
