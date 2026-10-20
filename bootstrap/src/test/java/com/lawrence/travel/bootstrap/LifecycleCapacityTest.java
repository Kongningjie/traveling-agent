package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.lifecycle.DiskAdmission;
import com.lawrence.travel.infrastructure.lifecycle.LocalBackupRetention;
import com.lawrence.travel.infrastructure.travel.TravelJson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 容量滞回和备份到期清理仅用合成文件，绝不触碰用户备份根。 */
class LifecycleCapacityTest {
    @TempDir Path root;

    /** 85%停止、80%恢复；未知容量不得乐观放行。 */
    @Test
    void hysteresis() {
        var ratio = new AtomicReference<>(0.79);
        var guard = new DiskAdmission(ratio::get);
        assertThat(guard.accepting()).isTrue();
        ratio.set(0.85);
        assertThat(guard.accepting()).isFalse();
        ratio.set(0.82);
        assertThat(guard.accepting()).isFalse();
        ratio.set(0.80);
        assertThat(guard.accepting()).isTrue();
        ratio.set(Double.NaN);
        assertThat(guard.accepting()).isFalse();
    }

    /**
     * * 只清理受管且到期的目录，未知文件触发失败且整份备份不部分删除。
     *
     * @throws Exception 合成文件写入失败
     */
    @Test
    void expirationAndUnknownFiles() throws Exception {
        var json = new InteractionJson(new TravelJson());
        var now = Instant.parse("2026-10-19T00:00:00Z");
        var old = Files.createDirectory(root.resolve("old"));
        String manifest =
                json.encode(
                        Map.of(
                                "managedBackup",
                                "traveling-agent-v3",
                                "createdAt",
                                now.minusSeconds(31L * 86400).toString(),
                                "expiresAt",
                                now.minusSeconds(86400).toString()));
        Files.writeString(old.resolve("manifest.json"), manifest);
        Files.writeString(old.resolve("postgres.dump"), "合成备份");
        var other = Files.createDirectory(root.resolve("unmanaged"));
        Files.writeString(other.resolve("keep.txt"), "不能清除");
        var retention = new LocalBackupRetention(root, json);
        assertThat(retention.prune(now)).isEqualTo(1);
        assertThat(other).exists();
        var unknown = Files.createDirectory(root.resolve("unknown"));
        Files.writeString(unknown.resolve("manifest.json"), manifest);
        Files.writeString(unknown.resolve("private.txt"), "未知文件");
        assertThatThrownBy(() -> retention.prune(now)).hasMessage("BACKUP_RETENTION_FAILED");
        assertThat(unknown.resolve("manifest.json")).exists();
    }
}
