package com.lawrence.travel.application.port;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** 受控旅行数据端口；返回供应商证据，不把搜索摘要当成已核实事实。 */
public interface TravelToolPort {
    /**
     * 查询一个明确操作，不接受任意 URL、HTTP 方法或认证参数。
     * @param operation 工具操作
     * @param arguments 业务参数；适配器只读取各操作白名单字段
     * @return 含来源和时间的未可信供应商资料
     */
    Evidence query(Operation operation, Map<String, String> arguments);

    /** 首版工具白名单，来源组由程序指定而不是模型提供。 */
    enum Operation {
        PLACE,
        ROUTE,
        WEATHER,
        SEARCH,
        EXTRACT
    }

    /**
     * 不可变证据外壳，payload 为 JSON 文本，不作为提示词指令执行。
     * @param source 不含凭证的来源
     * @param retrievedAt 获取时间
     * @param payload 规范化 JSON 产物；字段可能为 UNKNOWN
     */
    record Evidence(URI source, Instant retrievedAt, String payload) {}
}
