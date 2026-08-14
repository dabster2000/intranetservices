package dk.trustworks.intranet.recruitmentservice.services;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import jakarta.transaction.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Every Method B command that mutates a scheduling request must re-read it
 * inside its own transaction.
 *
 * <p><b>The bug this exists to prevent</b> (found on staging 2026-08-14):
 * {@code RecruitmentSchedulingResource} is not transactional, so the entity it
 * loads for the visibility checks is <em>detached</em> by the time a
 * {@code @Transactional} command receives it. Setters on a detached entity are
 * silently dropped. Everything the command does through queries — slot
 * releases, lifecycle events, outbox rows — still commits, so the request
 * <em>looks</em> cancelled: HTTP 200, a {@code SCHEDULING_CANCELLED} event, the
 * slots released. Only the request row never moves, the advance sweep finds a
 * live request with no live slots a minute later, and proposes fresh slots.
 * Cancel, handback and extend-window were all inoperable this way, which also
 * left the documented kill-switch unable to drain active requests.
 *
 * <p><b>Why this is an ArchUnit rule and not a behavioural test.</b> The
 * defect is invisible to any test that asserts on the value a command returns:
 * the in-memory entity carries the mutation faithfully, and it is only the
 * database that disagrees. Every existing unit test passed throughout. A test
 * that would have caught it must re-read from the database, which means the
 * {@code @QuarkusTest} tier — and that tier is not in the CI deploy gate, so a
 * regression there would rot unnoticed. This rule runs in the DB-free tier
 * that actually gates deploys, and fails the build the moment a command
 * forgets to re-attach.
 *
 * <p>A database-level reproduction belongs in the {@code @QuarkusTest} tier as
 * well; this rule is the part that holds the line on every push.
 */
@AnalyzeClasses(
        packages = "dk.trustworks.intranet.recruitmentservice",
        importOptions = ImportOption.DoNotIncludeTests.class)
class SchedulingCommandsReattachArchTest {

    private static final DescribedPredicate<JavaMethod> TAKE_A_SCHEDULING_REQUEST =
            new DescribedPredicate<>("take a RecruitmentSchedulingRequest parameter") {
                @Override
                public boolean test(JavaMethod method) {
                    return method.getRawParameterTypes().stream()
                            .map(JavaClass::getName)
                            .anyMatch(RecruitmentSchedulingRequest.class.getName()::equals);
                }
            };

    private static final ArchCondition<JavaMethod> RE_READ_THE_REQUEST =
            new ArchCondition<>("re-read the request via managed()") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean reattaches = method.getCallsFromSelf().stream()
                            .anyMatch(call -> "managed".equals(call.getTarget().getName()));
                    if (!reattaches) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " mutates a scheduling request that was "
                                        + "loaded outside this transaction, but never calls "
                                        + "managed(...) to re-read it — every setter it makes "
                                        + "will be silently discarded while its queries commit"));
                    }
                }
            };

    @ArchTest
    static final ArchRule transactional_commands_reattach_the_request =
            methods()
                    .that().areDeclaredIn(RecruitmentSchedulingService.class)
                    .and().areAnnotatedWith(Transactional.class)
                    .and(TAKE_A_SCHEDULING_REQUEST)
                    .should(RE_READ_THE_REQUEST)
                    .because("the resource is not transactional, so a command's request argument "
                            + "is detached — mutating it without re-reading loses the write while "
                            + "the surrounding queries still commit, which reads as success");
}
