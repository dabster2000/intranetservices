package dk.trustworks.intranet.recruitmentservice.events;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * P1 DoD: the recruitment event store has exactly one write path.
 * <p>
 * State tables and the event stream can never diverge only as long as every
 * append happens through {@link RecruitmentEventRecorder} inside the
 * command's transaction (spec §3.2). These rules fail the build when any
 * other class persists, mutates, or even instantiates
 * {@link RecruitmentEvent}.
 * <p>
 * P19 amendment (the P1 carry-over, consciously made): GDPR anonymization
 * is the single permitted mutation of the store —
 * {@code RecruitmentAnonymizerService} rewrites {@code pii}/{@code pii_state}
 * (never payload, never deletes) and is exempted from the append-only rule
 * by name. Every other class remains banned.
 * <p>
 * Known limitation: calls made through an upcast
 * ({@code PanacheEntityBase e = event; e.persist();}) or raw
 * {@code EntityManager.persist(Object)} carry a different bytecode owner and
 * escape these rules — the instantiation rule closes most of that gap
 * (nothing else can even construct the entity), the rest is code review.
 */
@AnalyzeClasses(packages = "dk.trustworks.intranet", importOptions = ImportOption.DoNotIncludeTests.class)
class RecruitmentEventSingleWriterArchTest {

    @ArchTest
    static final ArchRule only_the_recorder_persists_recruitment_events =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(RecruitmentEventRecorder.class.getName())
                    .should().callMethodWhere(
                            target(owner(equivalentTo(RecruitmentEvent.class)))
                                    .and(target(nameMatching("persist.*"))))
                    .because("the recruitment event stream has a single write path — "
                            + "append events via RecruitmentEventRecorder.record(builder) (spec §3.2)");

    @ArchTest
    static final ArchRule nobody_but_the_anonymizer_deletes_or_bulk_updates_recruitment_events =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(
                            dk.trustworks.intranet.recruitmentservice.services
                                    .RecruitmentAnonymizerService.class.getName())
                    .should().callMethodWhere(
                            target(owner(equivalentTo(RecruitmentEvent.class)))
                                    .and(target(nameMatching("delete.*|update.*"))))
                    .because("the event stream is append-only; the single permitted mutation is "
                            + "GDPR pii anonymization (P19) — RecruitmentAnonymizerService "
                            + "rewrites pii/pii_state and NOTHING else (no deletes, no payload "
                            + "edits; its referral-leg native query is part of the same "
                            + "reviewed path)");

    @ArchTest
    static final ArchRule only_the_recorder_instantiates_recruitment_events =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(RecruitmentEventRecorder.class.getName())
                    .should().callConstructorWhere(
                            target(owner(equivalentTo(RecruitmentEvent.class))))
                    .because("handlers describe events with RecruitmentEventBuilder; only the "
                            + "recorder materializes the entity (closes the EntityManager.persist loophole)");
}
