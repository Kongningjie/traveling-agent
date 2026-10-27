package com.lawrence.travel.bootstrap;

import com.lawrence.travel.domain.interaction.DecisionAnswer;
import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.domain.travel.TravelTask;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 决策版本、有限授权和部分回答规则；不以模型解释或 HTTP 接收冒充采纳。 */
class DecisionDomainTest {
    private final TaskScope scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID option = UUID.randomUUID();
    private final UUID allowedTarget = UUID.randomUUID();

    /**
     * @return 含两问题和一项预选建议的卡片，尚未采纳任何选项
     */
    private DecisionRequest request() {
        var a = new DecisionRequest.Question(first, "预算范围？", Map.of(option, "按组"), option);
        var b = new DecisionRequest.Question(second, "城市顺序？", Map.of(option, "自定"), null);
        return new DecisionRequest(
                UUID.randomUUID(),
                scope,
                UUID.randomUUID(),
                1,
                1,
                1,
                Map.of(first, a, second, b),
                true,
                Set.of(allowedTarget),
                DecisionRequest.State.OPEN);
    }

    /**
     * @return 尚未更新的任务值
     */
    private TravelTask task() {
        return new TravelTask(scope, 1, 1, null, Map.of());
    }

    /**
     * 构造只对第一个问题做出选择的回答。
     * @param request 当前卡片
     * @return 已收到但尚未解释的记录
     */
    private DecisionAnswer answer(DecisionRequest request) {
        return new DecisionAnswer(
                UUID.randomUUID(),
                request.id(),
                request.revision(),
                request.constraintRevision(),
                DecisionAnswer.Kind.ANSWER,
                Map.of(first, option),
                "",
                Set.of(),
                DecisionAnswer.State.RECEIVED);
    }

    /** 推荐预选和接收不等于采纳，解析完成之前不能更新卡片。 */
    @Test
    void recommendationAndReceiptDoNotConfirmRequirements() {
        var request = request();
        assertThat(request.remaining()).hasSize(2);
        assertThat(request.state()).isEqualTo(DecisionRequest.State.OPEN);
        var received = answer(request);
        assertThatThrownBy(() -> request.applyConfirmed(received, task(), Set.of(first), 2))
                .hasMessage("ILLEGAL_TRANSITION");
        assertThat(received.state()).isEqualTo(DecisionAnswer.State.RECEIVED);
        assertThat(request.remaining()).hasSize(2);
    }

    /** 部分回答只移除已解决内容，剩余问题跟随最新需求版本，旧回答明确拒绝。 */
    @Test
    void partialAnswerRevisesRemainingQuestionsWithoutLosingThem() {
        var original = request();
        var interpreted = answer(original).interpreted();
        var partial = original.applyConfirmed(interpreted, task(), Set.of(first), 2);
        assertThat(partial.remaining()).containsOnlyKeys(second);
        assertThat(partial.revision()).isEqualTo(2);
        assertThat(partial.constraintRevision()).isEqualTo(2);
        assertThat(partial.state()).isEqualTo(DecisionRequest.State.OPEN);
        var current = new TravelTask(scope, 2, 1, null, Map.of());
        assertThatThrownBy(() -> partial.applyConfirmed(interpreted, current, Set.of(second), 3))
                .hasMessage("STALE_DECISION");
        var rest =
                new DecisionAnswer(
                        UUID.randomUUID(),
                        partial.id(),
                        2,
                        2,
                        DecisionAnswer.Kind.ANSWER,
                        Map.of(second, option),
                        "",
                        Set.of(),
                        DecisionAnswer.State.INTERPRETED);
        assertThat(partial.applyConfirmed(rest, current, Set.of(second), 3).state())
                .isEqualTo(DecisionRequest.State.ANSWERED);
        assertThat(interpreted.applied().state()).isEqualTo(DecisionAnswer.State.APPLIED);
    }

    /** 未决定不能推进，选项回答不能顺便解决未答问题，新修改使旧卡片失效。 */
    @Test
    void undecidedAndUnansweredQuestionsCannotBeAdopted() {
        var request = request();
        var undecided =
                new DecisionAnswer(
                        UUID.randomUUID(),
                        request.id(),
                        1,
                        1,
                        DecisionAnswer.Kind.UNDECIDED,
                        Map.of(),
                        "",
                        Set.of(),
                        DecisionAnswer.State.INTERPRETED);
        assertThatThrownBy(() -> request.applyConfirmed(undecided, task(), Set.of(first), 2))
                .hasMessage("ILLEGAL_TRANSITION");
        assertThatThrownBy(undecided::applied).hasMessage("ILLEGAL_TRANSITION");
        var selected = answer(request).interpreted();
        assertThatThrownBy(() -> request.applyConfirmed(selected, task(), Set.of(first, second), 2))
                .hasMessage("INVALID_INPUT");
        assertThatThrownBy(
                        () ->
                                request.applyConfirmed(
                                        selected, task().fenceExecution(), Set.of(first), 2))
                .hasMessage("STALE_DECISION");
        assertThatThrownBy(
                        () ->
                                request.supersede()
                                        .applyConfirmed(selected, task(), Set.of(first), 2))
                .hasMessage("STALE_DECISION");
    }

    /** 明确授权仍受对象边界限制，普通回答不能夹带授权；无效选项也不能落需求。 */
    @Test
    void authorizationAndSelectionsStayInsideQuestionBoundary() {
        var request = request();
        var unauthorized =
                new DecisionAnswer(
                        UUID.randomUUID(),
                        request.id(),
                        1,
                        1,
                        DecisionAnswer.Kind.AUTHORIZE,
                        Map.of(),
                        "城市顺序由系统决定",
                        Set.of(UUID.randomUUID()),
                        DecisionAnswer.State.INTERPRETED);
        assertThatThrownBy(() -> request.applyConfirmed(unauthorized, task(), Set.of(second), 2))
                .hasMessage("INVALID_INPUT");
        var permitted =
                new DecisionAnswer(
                        UUID.randomUUID(),
                        request.id(),
                        1,
                        1,
                        DecisionAnswer.Kind.AUTHORIZE,
                        Map.of(),
                        "城市顺序由系统决定",
                        Set.of(allowedTarget),
                        DecisionAnswer.State.INTERPRETED);
        assertThat(request.applyConfirmed(permitted, task(), Set.of(second), 2).remaining())
                .containsOnlyKeys(first);
        var invalidOption =
                new DecisionAnswer(
                        UUID.randomUUID(),
                        request.id(),
                        1,
                        1,
                        DecisionAnswer.Kind.ANSWER,
                        Map.of(first, UUID.randomUUID()),
                        "",
                        Set.of(),
                        DecisionAnswer.State.INTERPRETED);
        assertThatThrownBy(() -> request.applyConfirmed(invalidOption, task(), Set.of(first), 2))
                .hasMessage("INVALID_INPUT");
        assertThatThrownBy(
                        () ->
                                new DecisionAnswer(
                                        UUID.randomUUID(),
                                        request.id(),
                                        1,
                                        1,
                                        DecisionAnswer.Kind.ANSWER,
                                        Map.of(first, option),
                                        "",
                                        Set.of(allowedTarget),
                                        DecisionAnswer.State.RECEIVED))
                .hasMessage("INVALID_INPUT");
    }
}
