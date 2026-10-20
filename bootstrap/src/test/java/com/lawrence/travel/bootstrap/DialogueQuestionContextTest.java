package com.lawrence.travel.bootstrap;

import com.lawrence.travel.application.interaction.DialogueQuestionContext;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.domain.interaction.DecisionRequest;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 问题压缩投影必须保留授权及选项双重含义，不靠提高Token上限或删除保护字段减小上下文。 */
class DialogueQuestionContextTest {
    /** 只重组重复索引；版本、规范值、展示文本、推荐和拒绝均可在投影中找回。 */
    @Test void projectionPreservesDecisionMeaningAndAvoidsDuplicatedOptionKeys() {
        UUID id = UUID.randomUUID(), questionId = UUID.randomUUID(), option = UUID.randomUUID();
        var scope = new TaskScope(UUID.randomUUID(), UUID.randomUUID());
        var question = new DecisionRequest.Question(questionId, "选择预算口径", Map.of(option, "不设上限"), null);
        var decision = new DecisionRequest(id, scope, UUID.randomUUID(), 2, 3, 4, Map.of(questionId, question), true, Set.of(questionId), DecisionRequest.State.OPEN);
        var card = new Negotiation.Card(decision, Map.of(questionId, new Negotiation.QuestionSpec("budgetRule", "预算缺项", "说明取舍", Map.of(option, "NONE"))), Set.of(option));
        var value = DialogueQuestionContext.from(card); String text = new TravelJson().encode(value);
        assertThat(value).containsEntry("id", id).containsEntry("scope", scope).containsEntry("generation", 2L)
                .containsEntry("revision", 3L).containsEntry("constraintRevision", 4L)
                .containsEntry("allowedAuthorizationScope", Set.of(questionId)).containsEntry("rejected", Set.of(option));
        assertThat(text).contains("不设上限", "NONE", "budgetRule", "说明取舍", "\"recommendedOption\":null");
        // 一个选项在问题/规格两套UUID索引中原本出现两次；投影只保留一次身份及两种含义。
        assertThat(text.split(option.toString(), -1).length - 1).isEqualTo(2); // 选项身份与拒绝集合各一次。
    }
}
