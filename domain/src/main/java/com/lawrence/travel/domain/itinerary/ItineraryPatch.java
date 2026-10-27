package com.lawrence.travel.domain.itinerary;

import com.lawrence.travel.domain.travel.TripWindow;
import java.util.List;
import java.util.UUID;

/**
 * 业务对象级补丁，不接受JSON路径、需求变更或生效指针写入。
 * @param expectedBaseHash 原生效版本摘要
 * @param operations 有序操作，同一对象至多一次
 * @param window 新旅行窗口，仅全程需求变化时允许，可空
 */
public record ItineraryPatch(
        String expectedBaseHash, List<Operation> operations, TripWindow window) {
    /** 限制规模；引用和范围在合并时验证。 */
    public ItineraryPatch {
        operations = List.copyOf(operations);
        if (expectedBaseHash == null
                || !expectedBaseHash.matches("[0-9a-f]{64}")
                || operations.size() > 500) throw new IllegalArgumentException("PATCH_INVALID");
    }

    /** 对象类别固定，不允许任意属性路径。 */
    public enum Kind {
        /** 城市段。 */
        CITY,
        /** 日期。 */
        DAY,
        /** 活动。 */
        ITEM,
        /** 交通。 */
        TRANSPORT,
        /** 住宿。 */
        ACCOMMODATION,
        /** 费用。 */
        COST,
        /** 备选。 */
        ALTERNATIVE
    }

    /** 动作必须与对象存在性相符。 */
    public enum Action {
        /** 新身份。 */
        ADD,
        /** 保留身份替换内容。 */
        REPLACE,
        /** 删除已有对象。 */
        REMOVE
    }

    /**
     * 严格类型载荷，REMOVE全部为空，其他动作只允许对应类别的一个载荷。
     * @param kind 类别
     * @param action 动作
     * @param id 稳定身份
     * @param city 城市
     * @param day 日期
     * @param item 活动
     * @param transport 交通
     * @param accommodation 住宿
     * @param cost 费用
     * @param alternative 备选
     * @param evidenceUses 该对象完整的新字段证据绑定，未修改对象不允许覆盖
     */
    public record Operation(
            Kind kind,
            Action action,
            UUID id,
            TripSnapshot.CityStay city,
            TripSnapshot.Day day,
            TripSnapshot.Item item,
            TripSnapshot.Transport transport,
            TripSnapshot.Accommodation accommodation,
            TripSnapshot.Cost cost,
            TripSnapshot.Alternative alternative,
            List<TripSnapshot.EvidenceUse> evidenceUses) {
        /** 形状校验，避免携带未声明的额外业务写入。 */
        public Operation {
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(action);
            java.util.Objects.requireNonNull(id);
            evidenceUses = List.copyOf(evidenceUses);
            var values =
                    new Object[] {city, day, item, transport, accommodation, cost, alternative};
            long count = java.util.Arrays.stream(values).filter(java.util.Objects::nonNull).count();
            if (action == Action.REMOVE
                    ? count != 0 || !evidenceUses.isEmpty()
                    : count != 1 || values[kind.ordinal()] == null)
                throw new IllegalArgumentException("PATCH_PAYLOAD_INVALID");
            if (evidenceUses.stream().anyMatch(u -> !u.objectId().equals(id)))
                throw new IllegalArgumentException("PATCH_EVIDENCE_SCOPE");
        }

        /**
         * @return 当前类别的强类型载荷，删除为空
         */
        public Object value() {
            return switch (kind) {
                case CITY -> city;
                case DAY -> day;
                case ITEM -> item;
                case TRANSPORT -> transport;
                case ACCOMMODATION -> accommodation;
                case COST -> cost;
                case ALTERNATIVE -> alternative;
            };
        }
    }
}
