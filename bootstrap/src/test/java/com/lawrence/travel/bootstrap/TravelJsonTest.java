package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 离线 JSON 格式及摘要边界；不将格式测试宣称为行程业务校验。
 */
class TravelJsonTest {
    private final TravelJson json = new TravelJson();

    /**
     * 对象键顺序和空白不影响摘要；数组顺序保留，避免改变行程顺序。
     */
    @Test
    void canonicalKeysKeepArrayOrder() {
        assertThat(json.encode(json.decode("{\"b\":[2,1],\"a\":{\"z\":0,\"c\":1}}", Object.class)))
                .isEqualTo("{\"a\":{\"c\":1,\"z\":0},\"b\":[2,1]}");
        assertThat(json.hash(json.encode(Map.of("a", 1)))).hasSize(64);
    }

    /**
     * 重复键、尾随 JSON、非法文本均固定失败，不泄露原始正文。
     */
    @Test
    void ambiguousJsonIsRejectedWithoutEchoingPayload() {
        for (String value : new String[] {"{\"a\":1,\"a\":2}", "{} {}", "invalid_json_body"}) {
            assertThatThrownBy(() -> json.decode(value, Object.class))
                    .isInstanceOf(TravelStorageFailure.class)
                    .hasMessage("INVALID_INPUT");
        }
    }

    /**
     * 金额小数不经 double 舍入；目标 ID 集合规范排序但其他行程数组保持顺序。
     */
    @Test
    void amountsKeepDecimalPrecisionAndTargetSetsHaveStableOrder() {
        String text = "{\"amount\":12345678901234567890.1234,\"targetIds\":[\"b\",\"a\"]}";
        assertThat(json.encode(json.decode(text, Object.class)))
                .isEqualTo("{\"amount\":12345678901234567890.1234,\"targetIds\":[\"a\",\"b\"]}");
    }
}
