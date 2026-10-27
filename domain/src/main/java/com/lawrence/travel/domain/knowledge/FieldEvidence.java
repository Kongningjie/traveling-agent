package com.lawrence.travel.domain.knowledge;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 字段级不可变证据。认知状态与缓存时效分开，摘录不被当作指令。
 * @param reference 精确正文引用
 * @param targetId 明确地点或交通对象身份
 * @param fieldPath 受支持字段
 * @param value 原文支持的值；未知为空
 * @param sourceType 程序确定的来源类别
 * @param sourceUrl 不含凭证的来源；用户资料可空
 * @param userMessageId 用户原消息引用，可空
 * @param retrievedAt 原始获取时间
 * @param publishedAt 原文明确发布时间，可空
 * @param validForDate 明确适用旅行日期，可空但不能推断覆盖其他日期
 * @param expiresAt 缓存排他截止时间
 * @param quote 支持该字段的连续摘录
 * @param artifactId 已提交原始阶段产物引用，存储投影时由来源阶段解析
 * @param status 原始认知状态；撤回和冲突通过追加通知表达
 */
public record FieldEvidence(
        EvidenceRecord.Reference reference,
        String targetId,
        String fieldPath,
        String value,
        SourceType sourceType,
        URI sourceUrl,
        UUID userMessageId,
        Instant retrievedAt,
        Instant publishedAt,
        LocalDate validForDate,
        Instant expiresAt,
        String quote,
        UUID artifactId,
        EvidenceRecord.Status status) {
    /** 来源可信程度不等同于事实真实性，种子与估算不能伪装成官方已核实。 */
    public enum SourceType {
        OFFICIAL,
        MAP_PROVIDER,
        USER,
        SEED,
        ESTIMATE,
        SEARCH_LEAD,
        UNKNOWN
    }

    /** 有界内容和明确时间；来源 URI 禁止认证字段、查询及片段，避免将密钥带入证据。 */
    public FieldEvidence {
        if (reference == null
                || targetId == null
                || targetId.isBlank()
                || targetId.length() > 200
                || fieldPath == null
                || !fieldPath.matches("[a-zA-Z][a-zA-Z0-9.]{0,79}")
                || value == null
                || value.length() > 16000
                || sourceType == null
                || retrievedAt == null
                || expiresAt == null
                || !expiresAt.isAfter(retrievedAt)
                || quote == null
                || quote.length() > 4000
                || status == null
                || (publishedAt != null && publishedAt.isAfter(retrievedAt))
                || (sourceUrl == null && userMessageId == null && sourceType != SourceType.UNKNOWN)
                || (sourceUrl != null
                        && (!"https".equals(sourceUrl.getScheme())
                                || sourceUrl.getHost() == null
                                || sourceUrl.getUserInfo() != null
                                || sourceUrl.getQuery() != null
                                || sourceUrl.getFragment() != null
                                || sourceUrl.getPort() != -1))
                || (status == EvidenceRecord.Status.KNOWN
                        && (value.isBlank()
                                || quote.isBlank()
                                || sourceType == SourceType.SEARCH_LEAD
                                || sourceType == SourceType.UNKNOWN)))
            throw new IllegalArgumentException("FIELD_EVIDENCE_INVALID");
    }

    /**
     * @param now 检查时刻
     * @param date 使用日期；有明确日期的证据必须精确匹配
     * @return 时间及日期适用；未知仍可能可缓存，但不能据此判断事实已知
     */
    public boolean reusableAt(Instant now, LocalDate date) {
        return !now.isBefore(retrievedAt)
                && now.isBefore(expiresAt)
                && java.util.Objects.equals(date, validForDate);
    }
}
