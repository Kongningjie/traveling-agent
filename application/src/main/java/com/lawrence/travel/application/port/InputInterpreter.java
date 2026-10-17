package com.lawrence.travel.application.port;

import com.lawrence.travel.application.interaction.Negotiation;
import java.util.UUID;

/** 自由文本提取端口，调用在业务事务之外；不拥有需求写权限。 */
public interface InputInterpreter {
    /**
     * @param runId 原运行预算
     * @param context 用户原文及权威需求
     * @return 有原文依据的提取，不等同于业务采纳
     */
    Negotiation.Interpretation interpret(UUID runId, Negotiation.Context context);
}
