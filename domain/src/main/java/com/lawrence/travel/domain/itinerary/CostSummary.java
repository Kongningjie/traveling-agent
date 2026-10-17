package com.lawrence.travel.domain.itinerary;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;

/** @param lowerCny 已知预算下界 @param upperCny 保守上界；有未知时为空 @param hasUnknown 是否有未知费用；不能按零展示 */
public record CostSummary(BigDecimal lowerCny, BigDecimal upperCny, boolean hasUnknown) {
    /** @param trip 完整行程 @return Java 汇总的金额，备选不重复计费；坏格式拒绝，不采信模型总价 */
    public static CostSummary calculate(TripSnapshot trip) {
        BigDecimal low = BigDecimal.ZERO, high = BigDecimal.ZERO; boolean unknown = false;
        for (var cost : trip.costEntries()) {
            if (cost.lower() == null || cost.quantity() == null || cost.unit() == null || cost.lower().signum() < 0 || cost.quantity().signum() <= 0 || !"CNY".equals(cost.currency())) throw new IllegalArgumentException("COST_INVALID");
            if (!cost.includedInBudget()) continue;
            BigDecimal multiplier = cost.quantity();
            if (cost.unit() == TripSnapshot.CostUnit.PER_PERSON) multiplier = multiplier.multiply(BigDecimal.valueOf(trip.window().travelers()));
            if (cost.unit() == TripSnapshot.CostUnit.PER_ROOM_NIGHT) {
                var stay = trip.accommodationStays().stream().filter(s -> s.id().equals(cost.relatedId())).findFirst().orElseThrow(() -> new IllegalArgumentException("ROOM_NIGHT_INVALID"));
                multiplier = BigDecimal.valueOf(ChronoUnit.DAYS.between(stay.checkIn().toLocalDate(), stay.checkOut().toLocalDate()) * stay.rooms());
                if (multiplier.signum() <= 0) throw new IllegalArgumentException("ROOM_NIGHT_INVALID");
            }
            low = low.add(cost.lower().multiply(multiplier));
            if (cost.upper() == null) unknown = true; else high = high.add(cost.upper().multiply(multiplier));
        }
        var billed = trip.costEntries().stream().map(TripSnapshot.Cost::relatedId).collect(java.util.stream.Collectors.toSet());
        unknown |= trip.items().stream().anyMatch(i -> (i.type() == TripSnapshot.ItemType.ACTIVITY || i.type() == TripSnapshot.ItemType.MEAL) && !billed.contains(i.id()))
                || trip.transportLegs().stream().anyMatch(t -> !billed.contains(t.id())) || trip.accommodationStays().stream().anyMatch(s -> !billed.contains(s.id()));
        return new CostSummary(low, unknown ? null : high, unknown);
    }
}
