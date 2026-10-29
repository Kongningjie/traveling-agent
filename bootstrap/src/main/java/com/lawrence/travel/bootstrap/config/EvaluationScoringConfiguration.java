package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.application.evaluation.EvaluationScoringService;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.infrastructure.evaluation.EvaluationScoringMapper;
import com.lawrence.travel.infrastructure.evaluation.PostgresEvaluationScoringStore;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 显式评分profile才装配私有复核入口；不创建平台发布线程或打开访问资格。 */
@Configuration(proxyBeanMethods = false)
@Profile("local & langfuse-scoring-validation")
public class EvaluationScoringConfiguration {
    /**
     * 装配同源事务下的评分服务；程序来源仍只允许可信执行器内部登记。
     *
     * @param mapper 评分SQL
     * @param transactions PG短事务
     * @param json 固定JSON
     * @return 评分服务
     */
    @Bean
    EvaluationScoringService evaluationScoringService(
            EvaluationScoringMapper mapper, BusinessTransactions transactions, TravelJson json) {
        return new EvaluationScoringService(
                new PostgresEvaluationScoringStore(mapper, json), transactions, Clock.systemUTC());
    }
}
