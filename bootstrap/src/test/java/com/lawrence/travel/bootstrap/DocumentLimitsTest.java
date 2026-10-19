package com.lawrence.travel.bootstrap;
import com.lawrence.travel.application.document.DocumentLimits;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
/** 配置只允许收紧当前安全协议上限，不能被错误数值静默放宽。 */
class DocumentLimitsTest {
    /** 缺失采用冻结初值；显式收紧被应用且容器只传非敏感白名单数值。 */
    @Test void defaultsAndStricterValuesAreExplicit() {assertThat(DocumentLimits.from(key->null)).isEqualTo(DocumentLimits.DEFAULT);var limits=DocumentLimits.from(key->key.equals("DOCUMENT_MAX_PAGES")?"20":null);assertThat(limits.pages()).isEqualTo(20);assertThat(limits.environment()).hasSize(8).containsEntry("DOCUMENT_MAX_PAGES",20L);}
    /** 非数值、零、溢出和超上限都固定拒绝，异常不能包含配置原文。 */
    @Test void invalidLimitsFailClosedWithoutEcho() {for(String text:java.util.List.of("SYNTHETIC_NOT_A_NUMBER","0","999999999999999999999","101"))assertThatThrownBy(()->DocumentLimits.from(key->key.equals("DOCUMENT_MAX_PAGES")?text:null)).hasMessage("DOCUMENT_LIMITS_INVALID");}
}
