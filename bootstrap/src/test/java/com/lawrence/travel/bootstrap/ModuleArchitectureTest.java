package com.lawrence.travel.bootstrap;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ModuleArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.lawrence.travel");

    @Test
    void domainRemainsIndependentOfFrameworksAndOuterModules() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.mybatis..", "io.agentscope..",
                        "..application..", "..api..", "..agent..", "..infrastructure..", "..bootstrap..")
                .check(CLASSES);
    }

    @Test
    void applicationDoesNotDependOnAdaptersOrBootstrap() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..api..", "..agent..", "..infrastructure..", "..bootstrap..")
                .check(CLASSES);
    }

    @Test
    void adaptersDoNotCallEachOther() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage("..agent..", "..infrastructure..", "..bootstrap..")
                .check(CLASSES);
        noClasses().that().resideInAPackage("..agent..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infrastructure..", "..bootstrap..")
                .check(CLASSES);
        noClasses().that().resideInAPackage("..infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..agent..", "..bootstrap..")
                .allowEmptyShould(true).check(CLASSES);
    }
}
