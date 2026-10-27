package com.lawrence.travel.bootstrap;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 检查实际生产类依赖方向，避免模块化单体在实现中退化为适配器任意互调。 */
class ModuleArchitectureTest {
    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.lawrence.travel");

    /** 验证领域规则不依赖框架及任何外层项目模块。 */
    @Test
    void domainRemainsIndependentOfFrameworksAndOuterModules() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.mybatis..",
                        "io.agentscope..",
                        "..application..",
                        "..api..",
                        "..agent..",
                        "..infrastructure..",
                        "..bootstrap..")
                .check(CLASSES);
    }

    /** 验证应用编排不反向引用 HTTP、Agent、存储适配或启动装配实现。 */
    @Test
    void applicationDoesNotDependOnAdaptersOrBootstrap() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..api..", "..agent..", "..infrastructure..", "..bootstrap..")
                .check(CLASSES);
    }

    /** 验证各适配器通过应用端口协作，且都不能依赖启动模块。 */
    @Test
    void adaptersDoNotCallEachOther() {
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..agent..", "..infrastructure..", "..bootstrap..")
                .check(CLASSES);
        noClasses()
                .that()
                .resideInAPackage("..agent..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..api..", "..infrastructure..", "..bootstrap..")
                .check(CLASSES);
        // infrastructure 已包含计量仓储和并发适配，规则必须覆盖这些真实生产类。
        noClasses()
                .that()
                .resideInAPackage("..infrastructure..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..api..", "..agent..", "..bootstrap..")
                .check(CLASSES);
    }
}
