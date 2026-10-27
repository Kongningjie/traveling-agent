package com.lawrence.travel.application.port;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.domain.interaction.DialogueIntent;

/**
 * 应用层到聊天理解Agent的端口，隔离业务编排与Harness/供应商SDK。
 *
 * <p>DialogueService.process调用interpret；生产实现DialogueIntentInterpreter返回结构化意图，
 * 应用层随后校验原文依据、权限及业务版本。实现不能直接保存需求或接受候选；
 * 测试替身只用于本地协议验证，不能作为真实模型质量结论。
 */
public interface DialogueInterpreter {
    /**
     * @param claim 已建立预算的私有输入
     * @return 严格结构化语义，调用方仍须校验原文及业务对象
     */
    DialogueIntent.Result interpret(Dialogue.Claim claim);
}
