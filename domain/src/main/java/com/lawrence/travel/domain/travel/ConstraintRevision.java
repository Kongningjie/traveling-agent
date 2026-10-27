package com.lawrence.travel.domain.travel;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;

/**
 * 已保存需求版本的不可变内容；完整 JSON schema 与数据库映射在存储阶段实现。
 * @param scope 归属
 * @param revision 单调需求版本
 * @param requirements 有来源及适用对象的通用约束／偏好
 * @param fixedArrangements 用户明确的固定安排
 */
public record ConstraintRevision(
        TaskScope scope,
        long revision,
        List<Requirement> requirements,
        List<FixedArrangement> fixedArrangements) {
    /** 校验稳定 ID 唯一并复制内容；不得原地覆盖历史版本。 */
    public ConstraintRevision {
        DomainViolation.require(
                scope != null && revision > 0 && requirements != null && fixedArrangements != null,
                INVALID_INPUT);
        DomainViolation.require(
                requirements.stream().allMatch(java.util.Objects::nonNull)
                        && fixedArrangements.stream().allMatch(java.util.Objects::nonNull),
                INVALID_INPUT);
        requirements = List.copyOf(requirements);
        fixedArrangements = List.copyOf(fixedArrangements);
        DomainViolation.require(
                requirements.stream().map(Requirement::id).distinct().count() == requirements.size()
                        && fixedArrangements.stream().map(FixedArrangement::id).distinct().count()
                                == fixedArrangements.size(),
                INVALID_INPUT);
    }

    /** 要求的强弱程度；系统不能自动将硬约束降为软偏好。 */
    public enum Kind {
        /** 必须满足。 */
        HARD,
        /** 可用于方案排序。 */
        SOFT
    }

    /** 区分用户确认事实与显式假设，推荐本身不能写成已确认要求。 */
    public enum Source {
        /** 用户直接提出。 */
        USER,
        /** 用户明确采纳系统建议。 */
        ACCEPTED_SUGGESTION,
        /** 用户已授权范围内由系统决定。 */
        AUTHORIZED_SYSTEM,
        /** 明示待核实假设，只能作为软项。 */
        ASSUMPTION
    }

    /**
     * 需求项稳定身份；value 是业务值，不作为代码或模型权限执行。
     * @param id 稳定要求 ID
     * @param field 需求字段
     * @param value 已解释值
     * @param kind 硬约束或软偏好
     * @param source 来源
     * @param targetIds 适用对象，空表示全旅行
     */
    public record Requirement(
            UUID id, String field, String value, Kind kind, Source source, Set<UUID> targetIds) {
        /** 禁止把假设自动提升为硬约束。 */
        public Requirement {
            DomainViolation.require(
                    id != null
                            && field != null
                            && !field.isBlank()
                            && value != null
                            && !value.isBlank()
                            && kind != null
                            && source != null
                            && targetIds != null
                            && !(kind == Kind.HARD && source == Source.ASSUMPTION),
                    INVALID_INPUT);
            DomainViolation.require(
                    targetIds.stream().allMatch(java.util.Objects::nonNull), INVALID_INPUT);
            targetIds = Set.copyOf(targetIds);
        }
    }

    /**
     * 带明确日期和时区的固定安排；住宿的占用语义在校验规则中单独处理。
     * @param id 稳定安排 ID
     * @param type 交通、住宿、预约或锁定对象等业务类型
     * @param startsAt 完整起始时间
     * @param endsAt 完整结束时间，可跨午夜
     * @param sourceMessageId 用户确认来源
     */
    public record FixedArrangement(
            UUID id,
            String type,
            ZonedDateTime startsAt,
            ZonedDateTime endsAt,
            UUID sourceMessageId) {
        /** 按实际时间线判断先后，不用 HH:mm 或城市标签代替明确时间。 */
        public FixedArrangement {
            DomainViolation.require(
                    id != null
                            && type != null
                            && !type.isBlank()
                            && startsAt != null
                            && endsAt != null
                            && endsAt.toInstant().isAfter(startsAt.toInstant())
                            && sourceMessageId != null,
                    INVALID_INPUT);
        }
    }
}
