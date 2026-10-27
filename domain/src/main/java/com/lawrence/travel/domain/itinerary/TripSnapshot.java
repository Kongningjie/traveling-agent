package com.lawrence.travel.domain.itinerary;

import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.travel.TripWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 可验证的 v1 完整快照；载荷自身不是用户授权或外部事实证明。
 * @param schemaVersion 格式版本
 * @param taskId 任务
 * @param constraintRevision 需求版本
 * @param baseVersionId 基础版本
 * @param window 旅行范围
 * @param cityStays 城市停留段
 * @param days 日历
 * @param items 实际占时活动
 * @param transportLegs 交通
 * @param accommodationStays 住宿
 * @param costEntries 唯一费用
 * @param alternatives 局部备选
 * @param evidenceRefs 实际证据引用
 * @param evidenceUses 对象字段与精确证据的绑定，旧快照缺失时为空
 */
public record TripSnapshot(
        int schemaVersion,
        UUID taskId,
        long constraintRevision,
        UUID baseVersionId,
        TripWindow window,
        List<CityStay> cityStays,
        List<Day> days,
        List<Item> items,
        List<Transport> transportLegs,
        List<Accommodation> accommodationStays,
        List<Cost> costEntries,
        List<Alternative> alternatives,
        Set<EvidenceRecord.Reference> evidenceRefs,
        List<EvidenceUse> evidenceUses) {
    /** 复制集合；业务错误由确定性报告表达，缺失必需字段属于 Schema 错误。 */
    public TripSnapshot {
        java.util.Objects.requireNonNull(window);
        java.util.Objects.requireNonNull(taskId);
        cityStays = List.copyOf(cityStays);
        days = List.copyOf(days);
        items = List.copyOf(items);
        transportLegs = List.copyOf(transportLegs);
        accommodationStays = List.copyOf(accommodationStays);
        costEntries = List.copyOf(costEntries);
        alternatives = List.copyOf(alternatives);
        evidenceRefs = Set.copyOf(evidenceRefs);
        evidenceUses = evidenceUses == null ? List.of() : List.copyOf(evidenceUses);
    }

    /**
     * @param schemaVersion 格式
     * @param taskId 任务
     * @param constraintRevision 需求
     * @param baseVersionId 基础版本
     * @param window 范围
     * @param cityStays 城市
     * @param days 日期
     * @param items 活动
     * @param transportLegs 交通
     * @param accommodationStays 住宿
     * @param costEntries 费用
     * @param alternatives 备选
     * @param evidenceRefs 精确引用
     */
    public TripSnapshot(
            int schemaVersion,
            UUID taskId,
            long constraintRevision,
            UUID baseVersionId,
            TripWindow window,
            List<CityStay> cityStays,
            List<Day> days,
            List<Item> items,
            List<Transport> transportLegs,
            List<Accommodation> accommodationStays,
            List<Cost> costEntries,
            List<Alternative> alternatives,
            Set<EvidenceRecord.Reference> evidenceRefs) {
        this(
                schemaVersion,
                taskId,
                constraintRevision,
                baseVersionId,
                window,
                cityStays,
                days,
                items,
                transportLegs,
                accommodationStays,
                costEntries,
                alternatives,
                evidenceRefs,
                List.of());
    }

    /**
     * @param objectId 活动／交通／费用身份
     * @param fieldPath 本次判断字段
     * @param reference 精确证据，不能只附一堆无关引用
     */
    public record EvidenceUse(UUID objectId, String fieldPath, EvidenceRecord.Reference reference) {
        /** 必须有明确对象及受控字段。 */
        public EvidenceUse {
            if (objectId == null
                    || reference == null
                    || fieldPath == null
                    || !java.util.Set.of(
                                    "openingHours",
                                    "price",
                                    "route",
                                    "reservation",
                                    "weather",
                                    "poi")
                            .contains(fieldPath))
                throw new IllegalArgumentException("EVIDENCE_USE_INVALID");
        }
    }

    /**
     * 城市可有多个停留段。
     * @param id 稳定 ID
     * @param cityId 城市
     * @param startsAt 起始
     * @param endsAt 结束
     */
    public record CityStay(UUID id, String cityId, ZonedDateTime startsAt, ZonedDateTime endsAt) {}

    /**
     * @param id 稳定 ID
     * @param date 旅行日期
     */
    public record Day(UUID id, LocalDate date) {}

    /** 用餐、休息、缓冲和候车均占用实际时间。 */
    public enum ItemType {
        /** 游玩。 */
        ACTIVITY,
        /** 用餐。 */
        MEAL,
        /** 休息。 */
        REST,
        /** 缓冲。 */
        BUFFER,
        /** 候车。 */
        WAIT
    }

    /**
     * @param id 稳定 ID
     * @param cityStayId 停留段
     * @param type 活动类型
     * @param title 展示名
     * @param locationKey 同地点识别键
     * @param startsAt 完整起始
     * @param endsAt 完整结束
     * @param fixedArrangementId 用户固定安排引用，可空
     */
    public record Item(
            UUID id,
            UUID cityStayId,
            ItemType type,
            String title,
            String locationKey,
            ZonedDateTime startsAt,
            ZonedDateTime endsAt,
            UUID fixedArrangementId) {}

    /**
     * @param id 稳定 ID
     * @param fromItemId 起点活动
     * @param toItemId 终点活动
     * @param startsAt 出发
     * @param endsAt 到达
     * @param durationMinutes 已查询／估算分钟，未知为空
     * @param estimated 是否估算
     */
    public record Transport(
            UUID id,
            UUID fromItemId,
            UUID toItemId,
            ZonedDateTime startsAt,
            ZonedDateTime endsAt,
            Long durationMinutes,
            boolean estimated) {}

    /**
     * 住宿不是白天活动冲突区间。
     * @param id 稳定 ID
     * @param cityStayId 所属停留
     * @param checkIn 入住
     * @param checkOut 离店
     * @param rooms 房间数
     * @param locationKey 酒店地点身份，旧快照可空，缺失时不能猜测费用证据
     */
    public record Accommodation(
            UUID id,
            UUID cityStayId,
            ZonedDateTime checkIn,
            ZonedDateTime checkOut,
            int rooms,
            String locationKey) {
        /**
         * @param id 身份
         * @param cityStayId 停留段
         * @param checkIn 入住
         * @param checkOut 离店
         * @param rooms 房间数
         */
        public Accommodation(
                UUID id,
                UUID cityStayId,
                ZonedDateTime checkIn,
                ZonedDateTime checkOut,
                int rooms) {
            this(id, cityStayId, checkIn, checkOut, rooms, null);
        }
    }

    /** 费用单位参与确定性计算。 */
    public enum CostUnit {
        /** 按人。 */
        PER_PERSON,
        /** 按组。 */
        PER_GROUP,
        /** 按房晚。 */
        PER_ROOM_NIGHT
    }

    /**
     * 未知上界不能按零；已付款是否包含也需用户明确。
     * @param id 稳定费用 ID
     * @param relatedId 关联活动／交通／住宿
     * @param category 收费分类，同一对象同一分类只计一次，允许门票及保险等不同费用
     * @param unit 单位
     * @param quantity 次数，房晚由住宿计算
     * @param lower 下界
     * @param upper 上界，未知为空
     * @param paid 已支付
     * @param includedInBudget 是否纳入
     * @param currency 当前只接受 CNY
     */
    public record Cost(
            UUID id,
            UUID relatedId,
            String category,
            CostUnit unit,
            BigDecimal quantity,
            BigDecimal lower,
            BigDecimal upper,
            boolean paid,
            boolean includedInBudget,
            String currency) {}

    /**
     * 备选不重复计入当前方案费用。
     * @param id 稳定 ID
     * @param replacesItemId 被替代活动
     * @param description 说明
     */
    public record Alternative(UUID id, UUID replacesItemId, String description) {}
}
