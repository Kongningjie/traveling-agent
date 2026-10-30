package com.lawrence.travel.infrastructure.tools;

import com.lawrence.travel.application.knowledge.EvidencePolicy;
import com.lawrence.travel.application.safety.SafetyGate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅用合成网页验证可见文字、来源标记及原安全检查，不请求供应商。 */
class ExtractedPageTextTest {
    /** 活动目标删除，显示标签及其旁边的日期/金额逐字保留。 */
    @Test
    void removesExecutableTargetsWithoutChangingVisibleFacts() {
        String text =
                "2026-11-07 门票40元 [预约](javascript:void(0))，其他[介绍](https://official.example/)。";
        String cleaned = ExtractedPageText.visibleText(text);
        assertThat(cleaned).isEqualTo("2026-11-07 门票40元 预约，其他[介绍](https://official.example/)。");
        assertThat(SafetyGate.local().check("TOOL_OUTPUT", cleaned).status())
                .isEqualTo(SafetyGate.Status.ALLOW);
        for (String link :
                List.of(
                        "[详情](JavaScript:go(')'))",
                        "[详情](vbscript:blocked)",
                        "[详情](<data:text/html,blocked>)",
                        "[详情]( javascript : void(0))")) {
            assertThat(ExtractedPageText.visibleText(link)).isEqualTo("详情");
        }
    }

    /** 不修改原响应；清理仅作用正文，供应商用量、原URL和原内容哈希可追溯。 */
    @Test
    void recordsTransformationAndKeepsResponseMetadata() {
        String text = "[预约](javascript:void(0))";
        var page = Map.of("url", "https://official.example/", "raw_content", text);
        Map<String, Object> raw = Map.of("results", List.of(page), "usage", Map.of("credits", 1));
        var result = ExtractedPageText.clean(raw);
        assertThat(raw.get("results")).isEqualTo(List.of(page));
        assertThat(result.get("usage")).isEqualTo(raw.get("usage"));
        var normalized = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertThat(normalized.get("url")).isEqualTo(page.get("url"));
        assertThat(normalized.get("raw_content")).isEqualTo("预约");
        assertThat(normalized.get("raw_content_original_hash"))
                .isEqualTo(EvidencePolicy.hash(text));
        assertThat(normalized.get("raw_content_transform"))
                .isEqualTo("ACTIVE_MARKDOWN_TARGETS_REMOVED_V1");
        assertThat(SafetyGate.local().check("TOOL_OUTPUT", result.toString()).status())
                .isEqualTo(SafetyGate.Status.ALLOW);
    }

    /** 未闭合、超深目标、裸协议及危险标签仍被拒绝；标签里的危险内容不能借清理放行。 */
    @Test
    void unsupportedOrDangerousTextStillFailsClosed() {
        for (String text :
                List.of(
                        "[预约](javascript:unfinished(",
                        "javascript:blocked",
                        "<script>blocked</script>",
                        "[javascript:blocked](javascript:void(0))",
                        "[链接](javascript:" + "(".repeat(33) + ")".repeat(34))) {
            assertThat(
                            SafetyGate.local()
                                    .check("TOOL_OUTPUT", ExtractedPageText.visibleText(text))
                                    .status())
                    .isEqualTo(SafetyGate.Status.REJECT);
        }
    }

    /** 普通链接和转义的示例不做重写；无变换响应保持原对象，不产生虚假的来源版本。 */
    @Test
    void ordinaryContentIsUnchangedAndNormalizationIsIdempotent() {
        String text = "[官网](https://official.example/) 普通旅行文字";
        assertThat(ExtractedPageText.visibleText(text)).isEqualTo(text);
        assertThat(ExtractedPageText.visibleText("\\[示例](javascript:blocked)"))
                .isEqualTo("\\[示例](javascript:blocked)");
        Map<String, Object> data = Map.of("results", List.of(Map.of("raw_content", text)));
        assertThat(ExtractedPageText.clean(data)).isSameAs(data);
        String cleaned = ExtractedPageText.visibleText("[预约](javascript:void(0))");
        assertThat(ExtractedPageText.visibleText(cleaned)).isEqualTo(cleaned);
    }

    /** 清理导航目标不降低证据标准；只有原文支持日期和值时才能标为已知。 */
    @Test
    void normalizedQuoteStillRequiresOriginalDateAndValue() {
        var json =
                new com.lawrence.travel.infrastructure.interaction.InteractionJson(
                        new com.lawrence.travel.infrastructure.travel.TravelJson());
        var policy = new EvidencePolicy(json, java.util.Set.of("official.example"));
        var date = java.time.LocalDate.parse("2026-11-07");
        var now = java.time.Instant.parse("2026-10-30T00:00:00Z");
        var data =
                ExtractedPageText.clean(
                        Map.of(
                                "results",
                                List.of(
                                        Map.of(
                                                "url",
                                                "https://official.example/",
                                                "raw_content",
                                                "2026-11-07 门票40元 [预约](javascript:void(0))"))));
        var raw =
                new com.lawrence.travel.application.port.TravelToolPort.Evidence(
                        java.net.URI.create("https://official.example/"),
                        now,
                        json.encode(
                                Map.of(
                                        "operation",
                                        "EXTRACT",
                                        "status",
                                        "AVAILABLE",
                                        "data",
                                        data)));
        var query =
                new com.lawrence.travel.application.knowledge.Research.Query(
                        "故宫",
                        "price",
                        com.lawrence.travel.application.port.TravelToolPort.Operation.EXTRACT,
                        Map.of("url", "https://official.example/"),
                        date);
        assertThat(
                        policy.normalize(
                                        query,
                                        raw,
                                        new com.lawrence.travel.application.knowledge.Research
                                                .Extraction("40元", "2026-11-07 门票40元", null, date),
                                        now)
                                .status())
                .isEqualTo(com.lawrence.travel.domain.knowledge.EvidenceRecord.Status.KNOWN);
        assertThat(
                        policy.normalize(
                                        query,
                                        raw,
                                        new com.lawrence.travel.application.knowledge.Research
                                                .Extraction("40元", "2026-11-07 门票40元", null, null),
                                        now)
                                .status())
                .isEqualTo(com.lawrence.travel.domain.knowledge.EvidenceRecord.Status.UNKNOWN);
        assertThat(
                        policy.normalize(
                                        query,
                                        raw,
                                        new com.lawrence.travel.application.knowledge.Research
                                                .Extraction("免费", "2026-11-07 门票40元", null, date),
                                        now)
                                .status())
                .isEqualTo(com.lawrence.travel.domain.knowledge.EvidenceRecord.Status.UNKNOWN);
    }
}
