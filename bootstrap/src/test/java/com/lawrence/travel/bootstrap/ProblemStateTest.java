package com.lawrence.travel.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.travel.domain.support.ProblemState;

import org.junit.jupiter.api.Test;

/** 问题状态机的确定性凭据和边界测试，不依赖LLM评分。 */
class ProblemStateTest {
    /** 新问题不能用重试或一步跳转冒充修复。 */
    @Test
    void cannotSkipTriage() {
        assertThat(ProblemState.NEW.permits(ProblemState.RESOLVED, "v1", "case-1", true)).isFalse();
        assertThat(ProblemState.NEW.permits(ProblemState.TRIAGED, "", "", false)).isTrue();
    }

    /** 修复和验证都需要版本与回归依据，验证还需明确通过。 */
    @Test
    void repairNeedsRegression() {
        assertThat(ProblemState.IN_PROGRESS.permits(ProblemState.RESOLVED, "v1", "", true))
                .isFalse();
        assertThat(ProblemState.IN_PROGRESS.permits(ProblemState.RESOLVED, "v1", "case-1", false))
                .isTrue();
        assertThat(ProblemState.RESOLVED.permits(ProblemState.VERIFIED, "v1", "case-1", false))
                .isFalse();
        assertThat(ProblemState.RESOLVED.permits(ProblemState.VERIFIED, "v1", "case-1", true))
                .isTrue();
        assertThat(ProblemState.VERIFIED.permits(ProblemState.REOPENED, "", "", false)).isTrue();
    }
}
