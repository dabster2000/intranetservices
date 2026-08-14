package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Subject;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bug this exists to prevent: <strong>the entire learner surface returning nothing,
 * for everyone, while looking exactly like a targeting or permission problem.</strong>
 *
 * <p>Found on staging 2026-08-14. {@code GET /me/requirements} answered
 * {@code Returning 0 requirements} for the caller, with no error anywhere and every
 * migration applied — the four seeded requirements simply reached nobody.
 *
 * <p>The cause is a trap in {@code User} that has bitten this codebase before (the
 * 2026-06-30 bonus-grid incident): {@code statuses}, {@code salaries}, {@code teams} and
 * {@code careerLevels} are {@code @Transient}, so {@code User.findById} / {@code User.list}
 * leave them <em>empty</em> rather than lazy. {@code getUserStatus(date)} then hits its
 * {@code .orElse(new UserStatus(..., TERMINATED, ...))} fallback and reports every user as
 * terminated. {@code CompetenceRequirementService.isActiveEmployee} reads that as "not an
 * employee", and {@link CompetenceAudienceMatcher#inAudience} refuses at its first guard —
 * before targeting is consulted at all. So the failure is total and silent, and it points
 * the investigation at the audience rules, which are fine.
 *
 * <p>These tests are deliberately pinned on {@link CompetenceAudienceMatcher} and the
 * status fallback rather than on {@code subjectOf} itself, because {@code subjectOf} needs
 * a database and this tier has none. What they lock down is the <em>property</em> the
 * hydration must produce: an active employee whose statuses were never loaded is
 * indistinguishable from a terminated one, and an untargeted requirement — which reaches
 * "everyone" — still reaches nobody in that state. Any future refactor that drops the
 * hydration reproduces exactly this, and the second test says so out loud.
 *
 * @see CompetenceMatrixService#hydrateStatuses(List) the shared helper every caller must use
 */
@DisplayName("Subject hydration: an unhydrated user is silently TERMINATED")
class CompetenceSubjectHydrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    private static User employee() {
        User user = new User();
        user.setUuid("11111111-1111-1111-1111-111111111111");
        user.setUsername("anna");
        return user;
    }

    /** The row that exists in the database for an active employee. */
    private static UserStatus activeStatus(String useruuid) {
        return new UserStatus(ConsultantType.CONSULTANT, StatusType.ACTIVE,
                TODAY.minusYears(3), 160, useruuid);
    }

    @Nested
    @DisplayName("the trap itself")
    class TheTrap {

        @Test
        @DisplayName("a user loaded without statuses reports TERMINATED, not ACTIVE")
        void unhydratedUserReadsAsTerminated() {
            User user = employee();

            // Exactly what User.findById leaves behind: the @Transient list, empty.
            assertTrue(user.getStatuses().isEmpty(),
                    "precondition: a bare user carries no statuses");
            assertFalse(CompetenceRequirementService.isActiveEmployee(user, TODAY),
                    "an unhydrated user must read as inactive — this is the trap, and the "
                            + "reason subjectOf has to hydrate before resolving a Subject");
        }

        @Test
        @DisplayName("the same user reads as ACTIVE once the statuses are attached")
        void hydratedUserReadsAsActive() {
            User user = employee();
            user.setStatuses(new ArrayList<>(List.of(activeStatus(user.getUuid()))));

            assertTrue(CompetenceRequirementService.isActiveEmployee(user, TODAY),
                    "with the statuses loaded the same person is an active employee");
        }
    }

    @Nested
    @DisplayName("the guard: subjectOf must actually hydrate")
    class TheGuard {

        /**
         * The regression test proper. The two nested classes around this one document the
         * <em>property</em>, but neither would fail if somebody deleted the hydration call —
         * they never touch {@code subjectOf}. This one does.
         *
         * <p>It is a <strong>source assertion</strong>, and that is a deliberate second
         * choice. The obvious test — {@code mockStatic(User.class)}, stub
         * {@code findById}, assert the resolved Subject is active — cannot be written:
         * {@code findById} is declared on {@code PanacheEntityBase}, not on {@code User},
         * and Mockito refuses to stub a static inherited from a parent class
         * ({@code MissingMethodInvocationException}). That is why
         * {@code CompetenceDecisionServiceTest} can mock {@code CompetenceAttempt.findByUuid}
         * — that one is declared on the entity itself — and why this cannot follow it.
         * The alternative, a {@code @QuarkusTest}, would put the guard in the tier that is
         * <em>not</em> in the CI gate, which is precisely where a guard is worth least.
         *
         * <p>So this reads the method body and asserts the call is present, in the same
         * spirit as {@code CompetenceLearnerDtoContractTest}'s source sweep. It is coarse,
         * and it cannot prove the call is correct — but it does fail the moment someone
         * removes it, which is the failure that actually happened.
         */
        @Test
        @DisplayName("subjectOf's body still calls hydrateStatuses")
        void subjectOfStillHydrates() throws IOException {
            Path source = Path.of("src/main/java/dk/trustworks/intranet/competenceservice"
                    + "/services/CompetenceRequirementService.java");
            assertTrue(Files.exists(source),
                    "Cannot find " + source.toAbsolutePath() + " — surefire is expected to run "
                            + "with the module root as the working directory");

            String body = methodBody(Files.readString(source), "public Subject subjectOf(");
            assertTrue(body.contains("hydrateStatuses("),
                    "subjectOf no longer hydrates the user's statuses. User.statuses is "
                            + "@Transient, so User.findById leaves it empty, getUserStatus falls "
                            + "back to a synthetic TERMINATED, and CompetenceAudienceMatcher "
                            + "refuses at its activeEmployee guard — before targeting is ever "
                            + "consulted. Every learner read then returns nothing, for every "
                            + "employee, and it looks like a targeting or permission bug. "
                            + "Body was:\n" + body);
        }

        /** The braces-balanced body of the first method whose declaration starts with {@code signature}. */
        private static String methodBody(String source, String signature) {
            int at = source.indexOf(signature);
            assertTrue(at >= 0, "Method not found: " + signature
                    + " — it was renamed or removed; re-point this guard at its replacement.");
            int open = source.indexOf('{', at);
            int depth = 0;
            for (int i = open; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return source.substring(open, i + 1);
                    }
                }
            }
            throw new AssertionError("Unbalanced braces after " + signature);
        }
    }

    @Nested
    @DisplayName("what it does to the audience")
    class TheConsequence {

        /**
         * The untargeted case is the sharpest one: all three target arrays absent means
         * "everyone" (spec §5.2), so if even that reaches nobody, the audience rules are
         * not what is wrong.
         */
        @Test
        @DisplayName("an untargeted requirement — which reaches everyone — reaches nobody unhydrated")
        void untargetedRequirementStillReachesNobody() {
            var everyone = new CompetenceAudienceMatcher.Targeting(null, null, null);

            Subject unhydrated = new Subject("u1", false, "tech-practice", java.util.Set.of(), java.util.Set.of());
            Subject hydrated = new Subject("u1", true, "tech-practice", java.util.Set.of(), java.util.Set.of());

            assertFalse(CompetenceAudienceMatcher.inAudience(unhydrated, everyone),
                    "the whole learner surface goes dark: 'everyone' reaches nobody when the "
                            + "subject was built from an unhydrated user");
            assertTrue(CompetenceAudienceMatcher.inAudience(hydrated, everyone),
                    "and comes back the moment the employment status is real");
        }

        @Test
        @DisplayName("a correctly targeted person is still excluded while unhydrated")
        void correctlyTargetedPersonIsExcludedUnhydrated() {
            var techOnly = new CompetenceAudienceMatcher.Targeting(
                    List.of("tech-practice"), null, null);

            Subject unhydrated = new Subject("u1", false, "tech-practice", java.util.Set.of(), java.util.Set.of());

            assertFalse(CompetenceAudienceMatcher.inAudience(unhydrated, techOnly),
                    "targeting is never even consulted — inAudience refuses at the "
                            + "activeEmployee guard, which is why this misreads as a targeting bug");
        }
    }
}
