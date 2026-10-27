package com.lawrence.travel.application.port;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.domain.interaction.DialogueIntent;

/**
 * 定义聊天意图识别和回复生成接口。
 */
public interface DialogueInterpreter {
    /**
     * @param claim 已建立预算的私有输入
     * @return 严格结构化语义，调用方仍须校验原文及业务对象
     */
    DialogueIntent.Result interpret(Dialogue.Claim claim);
}
