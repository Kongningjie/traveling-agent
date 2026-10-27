package com.lawrence.travel.application.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定评测协议；输入与裁判分离，领域探针、真实模型和真实工具的结论不得混算。 */
public final class Evaluation {
    /** 仅容纳评测协议。 */
    private Evaluation() { }
    /** 执行层次是报告的一部分，不能把本地规则探针换名为模型质量。 */
    public enum Mode { /** 本地领域／应用契约。 */ LOCAL_CONTRACT, /** 固定工具下的真实模型，需独立授权与接线。 */ REAL_MODEL, /** 动态外部工具，需另行授权。 */ REAL_TOOL }
    /** @param id 一期E01～E12、二期P01～P24或独立回归R01，回归不计入封存场景 @param partition DEVELOPMENT或HOLDOUT @param scenario 场景类型 @param input 原始合成需求 @param facts 固定结构化输入；二期script字段为完整会话步骤JSON，不包含裁判 @param seed 固定工具资料，不能当真实事实 */
    public record Case(String id, String partition, String scenario, String input, Map<String, String> facts, Map<String, String> seed) { }
    /** @param outcome 预期业务结果，与“生成成功”分开 @param required 必须由程序验证的检查项 */
    public record Oracle(String outcome, Set<String> required) { }
    /** @param version 用例版本 @param fingerprint 输入、种子和裁判总指纹 @param cases 已选择分区的输入 @param oracles 独立裁判，仅Runner读取 */
    public record Suite(String version, String fingerprint, List<Case> cases, Map<String, Oracle> oracles) { }
    /** @param outcome 实际结果 @param checks 程序检查，不由模型自评 @param metrics 实际量测；缺失不能补零 @param trace 固定阶段码，禁止放原始提示／异常或凭据 */
    public record Observation(String outcome, Map<String, Boolean> checks, Map<String, Long> metrics, List<String> trace) { }
    /** @param caseId 场景 @param partition 分区 @param repetition 本配置内重复序号 @param mode 实际层次 @param status PASS／FAIL／NOT_RUN @param outcome 业务结果，可空 @param failedChecks 未通过裁判项 @param metrics 实测指标，空不等于零 @param trace 固定失败轨迹 @param reason 跳过或失败的固定原因 */
    public record Row(String caseId, String partition, int repetition, Mode mode, String status, String outcome, List<String> failedChecks, Map<String, Long> metrics, List<String> trace, String reason) { }
    /** @param version 报告协议 @param suiteVersion 数据版本 @param suiteHash 数据总指纹 @param configuration 配置来源、代码及Prompt指纹 @param rows 按分区可分别统计的运行记录 */
    public record Report(String version, String suiteVersion, String suiteHash, Map<String, String> configuration, List<Row> rows) { }
    /** 不接收预期结果的执行端口；具体层次必须在装配时明确绑定。 */
    public interface Executor {
        /** @return 实际执行层次，防止本地替身被注册成真实模型实现 */
        Mode mode();
        /** @param input 固定输入，不含Oracle @return 有界、可解释的观察结果；异常由Runner固定分类 */
        Observation execute(Case input);
    }
}
