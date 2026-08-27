package com.flowpass.approval.arch;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guardrails for the DDD dependency direction and domain purity (charter section 10).
 *
 * <p>These rules are the automated equivalent of the hard red lines: the domain must stay
 * framework-agnostic and must never depend outward, the application layer must not reach into
 * infrastructure, and the contract must stay independent. A violation fails the build.</p>
 */
@AnalyzeClasses(packages = "com.flowpass.approval")
public class DependencyRulesTest {

    @ArchTest
    static final ArchRule domain_is_pure =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "org.apache.ibatis..",
                    "org.springframework.web..");

    @ArchTest
    static final ArchRule domain_depends_only_inward =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                    "..interfaces..",
                    "..application..",
                    "..infrastructure..",
                    "..contract..");

    @ArchTest
    static final ArchRule application_does_not_reach_infra =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                    "..infrastructure..",
                    "..interfaces..");

    @ArchTest
    static final ArchRule contract_is_independent =
            noClasses().that().resideInAPackage("..contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                    "..domain..",
                    "..application..",
                    "..infrastructure..",
                    "..interfaces..");
}
