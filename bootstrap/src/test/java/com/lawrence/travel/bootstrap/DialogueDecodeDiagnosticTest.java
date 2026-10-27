package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.interaction.DialogueDecodeFailure;
import com.lawrence.travel.application.interaction.DialogueDecodeFailure.Field;
import com.lawrence.travel.application.interaction.DialogueDecodeFailure.Kind;
import com.lawrence.travel.domain.interaction.DialogueIntent;
import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用实际Jackson和领域构造器的本地畸形输出矩阵，不调用模型，不保存不可信异常路径。 */
class DialogueDecodeDiagnosticTest {
    private final TravelJson json = new TravelJson();
    private final InteractionJson codec = new InteractionJson(json);

    /** 枚举、UUID和数组结构各自定位固定字段，不携带非法值。 */
    @Test
    void typeErrorsHaveWhitelistedPaths() {
        var value = valid();
        value.put(
                "intents",
                List.of(
                        Map.of(
                                "kind",
                                "PLANNING",
                                "action",
                                "SYNTHETIC_PRIVATE_ENUM",
                                "quote",
                                "修改")));
        rejects(json.encode(value), Kind.ENUM, Field.INTENT_ACTION);
        value = valid();
        value.put("facts", Map.of("SYNTHETIC_PRIVATE_KEY", "SYNTHETIC_PRIVATE_VALUE"));
        rejects(json.encode(value), Kind.SHAPE, Field.FACTS);
        value = valid();
        value.put("targetId", "SYNTHETIC_PRIVATE_UUID_INVALID");
        rejects(json.encode(value), Kind.UUID, Field.TARGET_ID);
        value = valid();
        value.put("resolvedInputs", List.of("SYNTHETIC_PRIVATE_UUID_INVALID"));
        rejects(json.encode(value), Kind.UUID, Field.RESOLVED_INPUTS);
    }

    /** 非软偏好使用REPLACE仍拒绝，仅定位构造对象，不猜测具体失败字段或放宽领域限制。 */
    @Test
    void constructorConstraintsRemainStrict() {
        var value = valid();
        value.put(
                "facts",
                List.of(
                        Map.of(
                                "field",
                                "startDate",
                                "value",
                                "2026-12-01",
                                "quote",
                                "修改",
                                "operation",
                                "REPLACE")));
        rejects(json.encode(value), Kind.CONSTRAINT, Field.FACTS);
        value = valid();
        value.put(
                "intents",
                List.of(Map.of("kind", "CONVERSATION", "action", "SUPPLEMENT", "quote", "修改")));
        rejects(json.encode(value), Kind.CONSTRAINT, Field.INTENTS);
    }

    /** 语法、重复键、尾随对象和空根均不自动修复或截断后接受。 */
    @Test
    void malformedJsonIsNotSilentlyRepaired() {
        rejects("SYNTHETIC_PRIVATE_BODY", Kind.SYNTAX, Field.ROOT);
        rejects("{\"reply\":\"a\",\"reply\":\"SYNTHETIC_PRIVATE_BODY\"}", Kind.SYNTAX, Field.ROOT);
        rejects(json.encode(valid()) + "{}", Kind.SHAPE, Field.ROOT);
        rejects("null", Kind.SHAPE, Field.ROOT);
    }

    /** 旧合法ADD缺省协议继续接受，独立通用JSON解码的异常契约不变。 */
    @Test
    void validAndGenericContractsArePreserved() {
        var result = codec.decode(json.encode(valid()), DialogueIntent.Result.class);
        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().getFirst().operation().name()).isEqualTo("ADD");
        assertThatThrownBy(() -> json.decode("SYNTHETIC_PRIVATE_BODY", Map.class))
                .isInstanceOf(com.lawrence.travel.application.travel.TravelStorageFailure.class)
                .hasMessage("INVALID_INPUT");
        for (var kind : Kind.values())
            for (var field : Field.values()) {
                var failure = new DialogueDecodeFailure(kind, field);
                assertThat(failure.diagnosticCode().length()).isLessThanOrEqualTo(48);
                assertThat(failure.getCause()).isNull();
                assertThat(failure.getSuppressed()).isEmpty();
            }
    }

    /**
     * @param body 合成错误产物
     * @param kind 固定类别
     * @param field 固定路径；诊断文本不能含合成私密标记
     */
    private void rejects(String body, Kind kind, Field field) {
        assertThatThrownBy(() -> codec.decode(body, DialogueIntent.Result.class))
                .isInstanceOf(DialogueDecodeFailure.class)
                .satisfies(
                        error -> {
                            var diagnostic = (DialogueDecodeFailure) error;
                            assertThat(diagnostic.kind()).isEqualTo(kind);
                            assertThat(diagnostic.field()).isEqualTo(field);
                            assertThat(diagnostic.getMessage()).doesNotContain("SYNTHETIC_PRIVATE");
                            assertThat(diagnostic.getCause()).isNull();
                            assertThat(diagnostic.getSuppressed()).isEmpty();
                        });
    }

    /**
     * @return 每次独立可变的合法业务对象，仅用于污染单个字段，不是模型质量预期
     */
    private Map<String, Object> valid() {
        var value = new LinkedHashMap<String, Object>();
        value.put(
                "intents",
                List.of(Map.of("kind", "PLANNING", "action", "SUPPLEMENT", "quote", "修改")));
        value.put(
                "facts",
                List.of(Map.of("field", "startDate", "value", "2026-12-01", "quote", "修改")));
        value.put("reply", "等待确认");
        value.put("clarification", "");
        value.put("targetId", null);
        value.put("category", "");
        value.put("value", "");
        value.put("city", "");
        return value;
    }
}
