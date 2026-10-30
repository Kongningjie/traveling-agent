package com.lawrence.travel.bootstrap;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 用合成文本检查定位指标，不执行网络请求或弱化安全门禁。 */
class ToolSafetyDiagnosticsTest {
    /** 长度、控制字符和活动内容类别分别可辨，不把原文或匹配片段写入结果。 */
    @Test
    void reportsOnlyBoundedCategories() {
        Map<String, String> samples =
                Map.of(
                        "activeMarkup",
                        "<script>PRIVATE_PAYLOAD</script>",
                        "activeUri",
                        "[PRIVATE_PAYLOAD](javascript:blocked)",
                        "eventAttribute",
                        "onerror=PRIVATE_PAYLOAD",
                        "nul",
                        "PRIVATE_PAYLOAD\0",
                        "credentialLike",
                        "authorization: bearer SYNTHETIC_PRIVATE_PAYLOAD");
        samples.forEach(
                (key, text) -> {
                    var result = ToolSafetyDiagnostics.inspect(text);
                    assertThat(result.get(key)).isEqualTo(true);
                    assertThat(result.toString())
                            .doesNotContain("PRIVATE_PAYLOAD", "javascript:", "bearer ");
                });
        assertThat(ToolSafetyDiagnostics.inspect("x".repeat(262145)).get("oversized"))
                .isEqualTo(true);
    }

    /** 正常旅行文本无活动内容特征，缺失与正常内容可以区分。 */
    @Test
    void ordinaryTextAndMissingInputAreDistinguished() {
        var result = ToolSafetyDiagnostics.inspect("故宫门票信息待核实");
        for (String key :
                java.util.List.of(
                        "missing",
                        "oversized",
                        "nul",
                        "activeMarkup",
                        "activeUri",
                        "eventAttribute",
                        "credentialLike")) assertThat(result.get(key)).isEqualTo(false);
        assertThat(ToolSafetyDiagnostics.inspect(null).get("missing")).isEqualTo(true);
    }

    /** 剩余协议只记录数量和链接结构特征，不返回目标或标签片段。 */
    @Test
    void remainingLinksExposeStructureWithoutContent() {
        String text =
                "{\"raw_content_transform\":\"ACTIVE_MARKDOWN_TARGETS_REMOVED_V2\","
                        + "\"raw_content\":\"[![PRIVATE_LABEL](icon)](javascript:void(0))\"}";
        var result = ToolSafetyDiagnostics.inspect(text);
        assertThat(result.get("remainingActiveUriCount")).isEqualTo(1);
        assertThat(result.get("activeUriInInlineTarget")).isEqualTo(true);
        assertThat(result.get("nestedMarkdownLabel")).isEqualTo(true);
        assertThat(result.get("textTransformPresent")).isEqualTo(true);
        assertThat(result.toString()).doesNotContain("PRIVATE_LABEL", "javascript:", "void(0)");
        assertThat(
                        ToolSafetyDiagnostics.inspect("\\[PRIVATE_LABEL](javascript:blocked)")
                                .get("escapedMarkdownLabel"))
                .isEqualTo(true);
    }
}
