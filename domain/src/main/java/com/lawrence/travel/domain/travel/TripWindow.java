package com.lawrence.travel.domain.travel;

import com.lawrence.travel.domain.rule.DomainViolation;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import static com.lawrence.travel.domain.rule.DomainViolation.Code.INVALID_INPUT;

/**
 * 首版已明确的规模边界；目的地集合不是城市停留段，同一城市允许多次停留。
 * @param cityIds 去重的目的地标识
 * @param startsOn 旅行首日
 * @param endsOn 旅行末日，包含当天
 * @param travelers 实际人数，不能根据人群标签推断
 */
public record TripWindow(Set<String> cityIds, LocalDate startsOn, LocalDate endsOn, int travelers) {
    /** 首版 1～3 城市、1～10 天、人数为正；超范围明确拒绝，不截断用户需求。 */
    public TripWindow {
        DomainViolation.require(
                cityIds != null
                        && !cityIds.isEmpty()
                        && cityIds.size() <= 3
                        && cityIds.stream().allMatch(id -> id != null && !id.isBlank())
                        && startsOn != null
                        && endsOn != null
                        && !endsOn.isBefore(startsOn)
                        && ChronoUnit.DAYS.between(startsOn, endsOn) < 10
                        && travelers > 0,
                INVALID_INPUT);
        cityIds = Set.copyOf(cityIds);
    }
}
