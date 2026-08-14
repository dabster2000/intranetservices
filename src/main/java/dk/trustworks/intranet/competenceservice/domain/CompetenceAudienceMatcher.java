package dk.trustworks.intranet.competenceservice.domain;

import java.util.List;
import java.util.Set;

/**
 * Decides whether one person is in one requirement's audience.
 *
 * <p><strong>This rule differs deliberately from {@code QuestionnaireService} and must not
 * be "corrected" to match it.</strong> The questionnaire reader chains its two filters, so
 * practice <em>and</em> team must both match. Applied here that produces a silent, serious
 * hole: <em>team leads carry no practice</em>. All nine hold their only MEMBER role on the
 * practice-less "Teamleads" org team and therefore resolve to {@code practiceUuid = null}
 * — intended behaviour, not a defect. Under questionnaire semantics a requirement targeted
 * at the Technology practice would exclude every technology team lead, precisely the
 * people who approve the pull requests this module is about.
 *
 * <p>So the audience is a <strong>union</strong>, and leadership of a targeted team counts
 * as membership of that team's practice.
 *
 * <p>Pure by design: the caller resolves the person's identity and team roles, this
 * decides. That keeps the regression test — a practice-less team lead holding an active
 * LEADER role on a team in the targeted practice IS in the audience — a plain unit test.
 */
public final class CompetenceAudienceMatcher {

    private CompetenceAudienceMatcher() {
    }

    /**
     * The three targeting arrays, already parsed. A {@code null} list means the column was
     * NULL or blank (absent); an <em>empty</em> list means an explicit {@code '[]'}.
     *
     * <p>That asymmetry is preserved from the questionnaire convention and it matters:
     * all-absent reaches everyone, while {@code '[]'} in every array reaches nobody, which
     * stays a valid way to park a requirement without deactivating it.
     */
    public record Targeting(List<String> practiceUuids,
                            List<String> teamUuids,
                            List<String> userUuids) {

        public boolean isUntargeted() {
            return practiceUuids == null && teamUuids == null && userUuids == null;
        }
    }

    /**
     * The person, reduced to what the decision needs.
     *
     * @param userUuid              their uuid
     * @param activeEmployee        only active employees are in any audience; terminated
     *                              people drop out of the matrix but keep their history
     * @param practiceUuid          their own practice, or {@code null} (every team lead)
     * @param activeTeamUuids       teams where they hold any active role
     * @param ledOrSponsoredPracticeUuids practices of teams where they hold an active
     *                              LEADER or SPONSOR role — the arm that includes team leads
     */
    public record Subject(String userUuid,
                          boolean activeEmployee,
                          String practiceUuid,
                          Set<String> activeTeamUuids,
                          Set<String> ledOrSponsoredPracticeUuids) {
    }

    public static boolean inAudience(Subject subject, Targeting targeting) {
        if (subject == null || targeting == null || !subject.activeEmployee()) {
            return false;
        }
        if (targeting.isUntargeted()) {
            return true;
        }
        return practiceHit(subject, targeting) || teamHit(subject, targeting) || userHit(subject, targeting);
    }

    /**
     * Either the person's own practice is targeted, or they lead/sponsor a team belonging
     * to a targeted practice. The second arm is what includes the nine team leads.
     */
    static boolean practiceHit(Subject subject, Targeting targeting) {
        List<String> targets = targeting.practiceUuids();
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        if (subject.practiceUuid() != null && targets.contains(subject.practiceUuid())) {
            return true;
        }
        Set<String> led = subject.ledOrSponsoredPracticeUuids();
        return led != null && led.stream().anyMatch(targets::contains);
    }

    /** Any active role on a targeted team — member type is irrelevant here. */
    static boolean teamHit(Subject subject, Targeting targeting) {
        List<String> targets = targeting.teamUuids();
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        Set<String> teams = subject.activeTeamUuids();
        return teams != null && teams.stream().anyMatch(targets::contains);
    }

    static boolean userHit(Subject subject, Targeting targeting) {
        List<String> targets = targeting.userUuids();
        return targets != null && !targets.isEmpty() && targets.contains(subject.userUuid());
    }
}
