package com.lawrence.travel.application.evaluation;

import java.time.Instant;

/** 整个平台的访问屏障；拒绝状态不区分账号、记录或清理任务，避免暴露运行明细。 */
public final class EvaluationPlatformAccess {
    /** 不实例化协议容器。 */
    private EvaluationPlatformAccess() {}

    /**
     * 单次请求的短期资格；调用者不能缓存后用于后续请求。
     *
     * @param verified 是否已由受控治理流程完成全量核验
     * @param notAfter 此时刻起禁止发送响应，至多为数据库查询后一秒
     */
    public record Decision(boolean verified, Instant notAfter) {
        /**
         * 按当前服务端时间检查资格；空期限一律拒绝。
         *
         * @param now 当前服务端时间
         * @return 是否仍在有效期内
         */
        public boolean allows(Instant now) {
            return verified && notAfter != null && now != null && now.isBefore(notAfter);
        }
    }

    /** 由PG提供当前访问资格；异常必须向上抛出，不能按空库放行。 */
    public interface Store {
        /**
         * 读取短期资格；存在任一未处理的到期/撤销记录就封闭整个入口。
         *
         * @return 当前资格，不返回评测正文
         */
        Decision decision();

        /** 启动或恢复前先关闭入口；仅此方法不代表已完成恢复核验。 */
        void closeAccess();
    }
}
