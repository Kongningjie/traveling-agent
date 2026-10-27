package com.lawrence.travel.domain.knowledge;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;

/**
 * 不可变字段证据契约；时效检查不保证事实真实性，冲突与关键未知仍需业务校验。
 * @param reference 不可变证据 ID／版本／哈希
 * @param field 被支持的业务字段
 * @param source 原始来源 URI，仅作证据，不授权抓取
 * @param retrievedAt 获取时间
 * @param validUntil 规则判断的排他有效截止时间
 * @param status 已知、未知或冲突
 */
public record EvidenceRecord(
        Reference reference,
        String field,
        URI source,
        Instant retrievedAt,
        Instant validUntil,
        Status status) {
    /** 事实认知状态，不把未知或冲突补成已确认。 */
    public enum Status {
        /** 有证据支持。 */
        KNOWN,
        /** 资料不足。 */
        UNKNOWN,
        /** 来源冲突未解。 */
        CONFLICT
    }

    /**
     * 字段证据的精确版本引用。
     * @param id 稳定证据 ID
     * @param revision 正版本号
     * @param contentHash 原文／产物 SHA256，小写标准化
     */
    public record Reference(UUID id, long revision, String contentHash) {
        /** 哈希是关联完整性条件，不能只凭 ID 沿用已被替代的版本。 */
        public Reference {
            DomainViolation.require(
                    id != null
                            && revision > 0
                            && contentHash != null
                            && contentHash.matches("[0-9a-fA-F]{64}"),
                    INVALID_INPUT);
            contentHash = contentHash.toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 时间区间必须明确，来源不允许携带 URI 用户认证字段。 */
    public EvidenceRecord {
        DomainViolation.require(
                reference != null
                        && field != null
                        && !field.isBlank()
                        && source != null
                        && source.isAbsolute()
                        && source.getUserInfo() == null
                        && retrievedAt != null
                        && validUntil != null
                        && validUntil.isAfter(retrievedAt)
                        && status != null,
                INVALID_INPUT);
    }

    /**
     * 判断已知证据是否仍在当前规则有效区间，旅行日期等适用性另行判断。
     * @param now 业务提供的检查时间
     * @return 已知且时间有效才为 true
     */
    public boolean knownAndFreshAt(Instant now) {
        DomainViolation.require(now != null, INVALID_INPUT);
        return status == Status.KNOWN && !now.isBefore(retrievedAt) && now.isBefore(validUntil);
    }
}
