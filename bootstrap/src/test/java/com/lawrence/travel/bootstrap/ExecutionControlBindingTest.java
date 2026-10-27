package com.lawrence.travel.bootstrap;

import com.lawrence.travel.bootstrap.config.ExecutionControlProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证操作配置的精确型号键和时间绑定，避免真实快照存在却被误解析成另一型号。 */
class ExecutionControlBindingTest {
    /** 验证带小数点及日期的型号键保留原样，默认开关不因额度存在而开启。 */
    @Test
    void bindsExactModelKeysWithoutEnablingCalls() {
        var values =
                new java.util.HashMap<String, Object>(
                        Map.of(
                                "travel.models.planner", "qwen3.8-27b",
                                "travel.limits.active-runs", "2",
                                "travel.limits.model-concurrency", "4",
                                "travel.limits.research-concurrency-per-run", "2",
                                "travel.limits.model-calls-per-run", "30",
                                "travel.limits.tool-calls-per-run", "60",
                                "travel.limits.repair-rounds", "2",
                                "travel.limits.model-timeout", "180s",
                                "travel.limits.active-time-per-run", "15m"));
        String prefix = "travel.quota-evidence[qwen3.8-27b].";
        values.put(prefix + "model-id", "qwen3.8-27b");
        values.put(prefix + "region", "test-region");
        values.put(prefix + "api", "TEST");
        values.put(prefix + "remaining-tokens", "100");
        values.put(prefix + "verified-at", "2026-10-15T10:00:00Z");
        values.put(prefix + "valid-until", "2026-10-15T11:00:00Z");
        values.put(prefix + "expires-at", "2026-10-16T00:00:00Z");
        values.put(prefix + "stop-protection-confirmed", "true");
        var properties =
                new Binder(new MapConfigurationPropertySource(values))
                        .bind("travel", Bindable.of(ExecutionControlProperties.class))
                        .get();
        assertThat(properties.externalCallsEnabled()).isFalse();
        assertThat(properties.quotaEvidence()).containsOnlyKeys("qwen3.8-27b");
        assertThat(properties.quotaEvidence().get("qwen3.8-27b").remainingTokens()).isEqualTo(100);
        assertThat(properties.limits().runLimits().modelCalls()).isEqualTo(30);
    }
}
