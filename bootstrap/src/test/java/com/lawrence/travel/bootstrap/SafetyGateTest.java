package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.safety.SafetyGate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 确定性边界断言；合成秘密不等于真实凭据，不由模型自评安全。 */
class SafetyGateTest {
    /** 正常旅行健康／宗教／饮食要求不得因关键词被删除。 */
    @Test
    void ordinaryTravelConstraintsAreAllowed() {
        for (String text :
                List.of(
                        "花生过敏，需要无障碍通道",
                        "清真饮食，不喝酒",
                        "我有健康限制，每天走路不超过两公里",
                        "不要停止规划",
                        "用户提到忽略旧建议，但并未授权删除偏好"))
            assertThat(SafetyGate.local().check("INPUT", text).status())
                    .isEqualTo(SafetyGate.Status.ALLOW);
    }

    /** 输入、摘要和输出的基础危险载荷统一拒绝，结果不回显原文。 */
    @Test
    void scriptsAndDangerousLinksAreRejected() {
        for (String text :
                List.of(
                        "<script>alert(1)</script>",
                        "[资料](javascript:alert(1))",
                        "data:text/html,<h1>unsafe</h1>",
                        "<img onerror=alert(1)>")) {
            var result = SafetyGate.local().check("OUTPUT", text);
            assertThat(result.status()).isEqualTo(SafetyGate.Status.REJECT);
            assertThat(result.problemId()).isNotNull();
            assertThat(result.toString()).doesNotContain(text);
        }
    }

    /** 进程内秘密精确匹配，不输出原值、不把凭据交给LLM检查。 */
    @Test
    void exactSecretIsNeverReturnedInFailure() {
        String synthetic = "SYNTHETIC_LOCAL_ONLY_CREDENTIAL";
        var gate = new SafetyGate(() -> List.of(synthetic), (point, result) -> {});
        assertThatThrownBy(() -> gate.require("OUTPUT", "回复" + synthetic))
                .isInstanceOf(SafetyGate.Failure.class)
                .hasMessage("SAFETY_REJECT");
    }

    /** 检查器故障与拒绝分开，不能默认为ALLOW。 */
    @Test
    void dependencyFailureClosesCheck() {
        var gate =
                new SafetyGate(
                        () -> {
                            throw new IllegalStateException("SYNTHETIC_DEPENDENCY_FAILURE");
                        },
                        (point, result) -> {});
        assertThat(gate.check("INPUT", "正常旅行").status()).isEqualTo(SafetyGate.Status.UNAVAILABLE);
    }

    /** 审计不可用也失败关闭，不通过隐藏日志泄露载荷。 */
    @Test
    void unavailableAuditCannotBeDeclaredSuccess() {
        var gate =
                new SafetyGate(
                        List::of,
                        (point, result) -> {
                            throw new IllegalStateException("SYNTHETIC_AUDIT_FAILURE");
                        });
        assertThat(gate.check("OUTPUT", "javascript:blocked").status())
                .isEqualTo(SafetyGate.Status.UNAVAILABLE);
    }
}
