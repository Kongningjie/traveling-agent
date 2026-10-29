package com.lawrence.travel.application.evaluation;

import java.time.Instant;
import java.util.UUID;

/** 评测发布登记协议；只记录发送资格和回执指纹，不执行平台请求或证明物理清理。 */
public final class EvaluationPublication {
    /** 不实例化协议容器。 */
    private EvaluationPublication() {}

    /** 单次尝试的持久状态；UNKNOWN不得自动重发。 */
    public enum State {
        /** 可能未发送，也可能已发送但未收到回执。 */
        UNKNOWN,
        /** 平台已接收，尚未查询确认。 */
        ACCEPTED,
        /** 受控适配器已查询确认，不代表物理删除。 */
        CONFIRMED,
        /** 已撤销、到期或存在删除标记。 */
        BLOCKED
    }

    /**
     * 可信发布器的不可变请求；不接受浏览器传入的身份或正文。
     *
     * @param owner 原始运行归属
     * @param run 原始运行
     * @param kind TRACE、DATASET或SCORE
     * @param locatorHash 规范化平台位置指纹
     * @param payloadHash 原始待发送内容指纹，不存正文
     */
    public record Request(
            UUID owner,
            UUID run,
            EvaluationRetention.CopyKind kind,
            String locatorHash,
            String payloadHash) {
        /** 校验身份、允许的发布类别和指纹。 */
        public Request {
            EvaluationRetention.require(
                    owner != null
                            && run != null
                            && publishable(kind)
                            && EvaluationRetention.hash(locatorHash)
                            && EvaluationRetention.hash(payloadHash));
        }
    }

    /**
     * 首次登记得到的尝试身份；必须提交短事务后才能发送，丢失后不能自动重新申请。
     *
     * @param request 原始发布请求
     * @param attempt 本次随机尝试ID
     * @param expiresAt 原始运行期限
     */
    public record Permit(Request request, UUID attempt, Instant expiresAt) {
        /** 拒绝缺失的尝试身份和原始期限。 */
        public Permit {
            EvaluationRetention.require(request != null && attempt != null && expiresAt != null);
        }
    }

    /** PG发布端口；没有发送器、恢复重放或开放平台访问的副作用。 */
    public interface Store {
        /**
         * 在同一短事务中登记副本和UNKNOWN尝试；同一位置只能首次领取，不能换ID重发。
         *
         * @param request 可信原始请求
         * @param attempt 首次随机尝试
         * @return 原始期限；冲突、越权、删除或到期为null
         */
        Instant claim(Request request, UUID attempt);

        /**
         * 发送前原子消耗许可并复核数据库期限和删除屏障；同一许可只能成功一次。
         *
         * @param permit 首次登记的许可
         * @return 是否首次消耗成功；不保证网络发出后仍活跃
         */
        boolean beginSend(Permit permit);

        /**
         * 保存接收/查询回执；已阻断的迟到回执清空旧清理证明并增加副本代次。
         *
         * @param permit 原始尝试
         * @param outcome 仅ACCEPTED或CONFIRMED
         * @param evidenceHash 接收或查询证据指纹，不是删除证明
         * @return 保存后的状态；重复、过时、越权为null
         */
        State acknowledge(Permit permit, State outcome, String evidenceHash);

        /**
         * 将已撤销/到期运行的最早阻断点单独保存；活跃运行不能被此清理入口删除。
         *
         * @param run 原始运行
         * @return 是否已有或成功登记阻断标记
         */
        boolean seal(UUID run);
    }

    /**
     * 只允许平台发布类别，缓存和本地文件不借此通道发送。
     *
     * @param kind 副本类别
     * @return 是否属于首版发布范围
     */
    private static boolean publishable(EvaluationRetention.CopyKind kind) {
        return kind == EvaluationRetention.CopyKind.TRACE
                || kind == EvaluationRetention.CopyKind.DATASET
                || kind == EvaluationRetention.CopyKind.SCORE;
    }
}
