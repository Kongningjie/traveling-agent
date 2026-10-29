package com.lawrence.travel.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.travel.infrastructure.evaluation.LangfusePilotSuite;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 只读既有合成classpath资源，不上传数据集或运行评测。 */
class LangfusePilotSuiteTest {
    /** 五例选择及判据指纹稳定，输入与Oracle分离，原封存数据不改。 */
    @Test
    void loadsFiveExistingCasesWithStableFingerprint() {
        var loader = new LangfusePilotSuite(LangfusePilotSuiteTest::resource);
        var first = loader.load();
        var second = loader.load();
        assertEquals(
                List.of("P01", "P02", "R01", "R02", "R03"),
                first.cases().stream().map(c -> c.id()).toList());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertTrue(
                first.cases().stream()
                        .allMatch(
                                c ->
                                        "DEVELOPMENT".equals(c.partition())
                                                && !c.facts().containsKey("oracle")));
        assertTrue(first.oracles().get("R03").required().contains("MESSAGE_DOCUMENT_BOUND"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.cases().getFirst().facts().put("oracle", "forged"));
    }

    /** 补充资源变化不能自动刷新预期指纹以掩盖用例变化。 */
    @Test
    void rejectsChangedRegressionFixture() {
        var loader =
                new LangfusePilotSuite(
                        name ->
                                name.endsWith("requirements.json")
                                        ? "[]".getBytes(StandardCharsets.UTF_8)
                                        : resource(name));
        assertThrows(IllegalArgumentException.class, loader::load);
    }

    /**
     * 有界读取仓库固定合成资源。
     *
     * @param name 固定classpath名称
     * @return 原始字节，不读取用户附件
     */
    private static byte[] resource(String name) {
        try (var stream = LangfusePilotSuiteTest.class.getResourceAsStream("/" + name)) {
            if (stream == null) throw new IllegalArgumentException("FIXTURE_MISSING");
            return stream.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("FIXTURE_READ_FAILED");
        }
    }
}
