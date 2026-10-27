package com.lawrence.travel.domain.memory;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.STALE_REVISION;

/**
 * 用户级稳定偏好版本，不随单条偏好删除而丢失；与条目在同一事务中检查和推进。
 * @param userId 所属用户
 * @param revision 从 0 开始的版本
 */
public record PreferenceVersion(UUID userId, long revision) {
    /** 保留空偏好集合对应的零版本，拒绝缺失归属和负版本。 */
    public PreferenceVersion {
        DomainViolation.require(userId != null && revision >= 0, INVALID_INPUT);
    }

    /**
     * 显式偏好变化后推进版本，写事务必须在稳定版本行再次检查。
     * @param expected 当前读取版本
     * @return 下一版本
     */
    public PreferenceVersion next(long expected) {
        DomainViolation.require(revision == expected, STALE_REVISION);
        return new PreferenceVersion(userId, Math.incrementExact(revision));
    }
}
