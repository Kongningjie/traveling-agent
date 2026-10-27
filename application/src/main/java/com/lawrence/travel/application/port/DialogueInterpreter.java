package com.lawrence.travel.application.port;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.domain.interaction.DialogueIntent;

/** 协调角色的一次理解与字段提取；不写业务，不独立创建预算。 */
public interface DialogueInterpreter {
    /**
     * @param claim 已建立预算的私有输入
     * @return 严格结构化语义，调用方仍须校验原文及业务对象
     */
    DialogueIntent.Result interpret(Dialogue.Claim claim);
}
