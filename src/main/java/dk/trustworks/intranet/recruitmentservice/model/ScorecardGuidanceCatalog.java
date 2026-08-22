package dk.trustworks.intranet.recruitmentservice.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Trustworks interview framework as interviewer-facing coaching, keyed by
 * {@link ScorecardAttribute#code()}.
 * <p>
 * Single source of truth for every surface: the web scorecard renders it as
 * hover help, the Slack modal as input hints, and the position editor as a
 * preview of what interviewers will be asked. Edit the wording here and all
 * three move together — including on positions created before the edit, since
 * guidance is resolved by code at render time (see {@link ScorecardGuidance}).
 * <p>
 * The catalog also carries {@link #CULTURE_FIT_LEGACY_CODE}, the predecessor of
 * {@code CULTURE}: positions created before the six-subject framework keep
 * their snapshotted template, and their in-flight interviews still deserve
 * help text.
 */
public final class ScorecardGuidanceCatalog {

    private ScorecardGuidanceCatalog() {
    }

    /** Pre-2026-08 code for the culture subject, still live on older positions. */
    public static final String CULTURE_FIT_LEGACY_CODE = "CULTURE_FIT";

    /**
     * How to run a six-subject sitting. Shown above the scorecard and in the
     * position editor: six is the ceiling for one interview, so the honest
     * pattern is depth on a few subjects and evidence-backed scoring on the
     * rest — marching through all six as a checklist is what produces
     * backfilled 3s.
     */
    public static final String USAGE_NOTE =
            "Six subjects is the ceiling for one sitting. Probe deep on three or four, "
                    + "and score the rest from what naturally surfaced — with one line of "
                    + "concrete evidence per score in your notes. Working through all six as "
                    + "a checklist is what produces backfilled 3s. The overall recommendation "
                    + "is a separate, holistic call: if it diverges from the subject scores, "
                    + "that is signal worth saying out loud, not an error to correct.";

    private static final ScorecardGuidance WHY_CONSULTING = new ScorecardGuidance(
            "WHY_CONSULTING",
            "Why consulting",
            "Motivation for the consultant role itself — with a realistic picture of "
                    + "its uncomfortable parts.",
            "Motivation for the consultant role itself — not just for Trustworks or the "
                    + "domain. A realistic picture including the uncomfortable parts "
                    + "(utilization, being measured on client value, representing an unpopular "
                    + "recommendation). For experienced profiles: evidence they have genuinely "
                    + "operated as a consultant — big-corp profiles with “one Danske Bank "
                    + "line on the CV” often carry too much of their own way of working.",
            List.of(
                    "What does a consultant do that a permanent employee in the same seat doesn't?",
                    "Tell me about representing an advisory position the client didn't like.",
                    "(Seniors) What will you have to unlearn here?"),
            List.of(
                    "Wants out of their current job more than into consulting; treats it as a "
                            + "waiting room; no realistic picture of the role.",
                    "Drawn to variety/prestige but hasn't confronted the downsides; big-corp "
                            + "habits unexamined.",
                    "Realistic picture incl. the hard parts; motivation anchored in client value "
                            + "and variety; seniors have demonstrably worked as consultants.",
                    "Talks about consulting as a craft, with concrete examples of choosing the "
                            + "harder advisory path; energizes the interviewer about the role."));

    private static final String CULTURE_WHAT =
            "The DNA made observable, both halves. Good People: honest, empathetic, outgoing; "
                    + "holds multiple perspectives “in a spacious manner”; balances fun, "
                    + "work and life. Talent & Passion / Continuous Improvement: a fast learner on "
                    + "a steep curve who shares what they learn — the behaviour practices, faglige "
                    + "fredage and vidensdage run on. The test is “someone you want to work "
                    + "with, professionally and personally” — explicitly not “similar to us”.";

    private static final List<String> CULTURE_PROBES = List.of(
            "Tell me about a colleague you disagreed with and still made successful.",
            "What do colleagues get from working with you that isn't on your CV?",
            "What have you taught yourself in the last six months, and who did you pass it on to?",
            "What would you run a faglig fredag session on?");

    private static final List<String> CULTURE_ANCHORS = List.of(
            "Talks down former colleagues; empathy absent; one fixed perspective; learning "
                    + "stopped at the last certification; hoards knowledge.",
            "Pleasant but guarded or rehearsed; struggles to genuinely hold another "
                    + "perspective; learns when forced, shares only if asked.",
            "Honest, warm, concrete; owns failures unprompted; real steep-curve examples; "
                    + "teaches or presents by habit; you'd staff them with your best client tomorrow.",
            "Would visibly raise the room; a learning machine with a sharing reflex — arrives "
                    + "with an idea for what they'd contribute to a practice in month one.");

    private static final String CULTURE_SHORT_HINT =
            "Someone you want to work with — honest, empathetic, a fast learner "
                    + "who shares what they learn.";

    private static final ScorecardGuidance CULTURE = new ScorecardGuidance(
            "CULTURE",
            "Culture — Good People, learning & sharing",
            CULTURE_SHORT_HINT, CULTURE_WHAT, CULTURE_PROBES, CULTURE_ANCHORS);

    /** Same subject under its pre-2026-08 code, for positions snapshotted before the rename. */
    private static final ScorecardGuidance CULTURE_FIT = new ScorecardGuidance(
            CULTURE_FIT_LEGACY_CODE,
            "Culture fit",
            CULTURE_SHORT_HINT, CULTURE_WHAT, CULTURE_PROBES, CULTURE_ANCHORS);

    private static final ScorecardGuidance SELF_LEADERSHIP = new ScorecardGuidance(
            "SELF_LEADERSHIP",
            "Self-leadership & structure",
            "Can they run their own week at a client with no one steering?",
            "Can they run their own week at a client with no one steering? Career-model "
                    + "behaviours: plan and structure own work, prioritize, align expectations up "
                    + "front (forventningsafstemning), flag slippage early, sustainable pace with "
                    + "boundaries.",
            List.of(
                    "Describe a week with three deliverables and nobody managing you — how did you run it?",
                    "When did you last flag early that something would slip?",
                    "How do you decide what not to do?"),
            List.of(
                    "Needs task-level direction; deadline surprises; no prioritization — or "
                            + "endless hours as the only coping tool.",
                    "Structured when the frame is handed over; slippage surfaces late; hesitant "
                            + "to renegotiate expectations.",
                    "Plans own work, aligns expectations up front, raises risk early, keeps a "
                            + "sustainable pace.",
                    "Creates structure others adopt; expectation alignment is a reflex; proven "
                            + "delivery under ambiguity without steering."));

    private static final ScorecardGuidance UNCERTAINTY = new ScorecardGuidance(
            "UNCERTAINTY",
            "Handling uncertainty",
            "Do they create clarity when the brief is unclear — or wait for it?",
            "Comfort and effectiveness when the brief is unclear, the data is missing, or a "
                    + "committed deadline meets fixed scope (leverancekultur: timebox, "
                    + "“lever effektivt, brænd færrest timer”). Do they create "
                    + "clarity or wait for it?",
            List.of(
                    "Tell me about landing on a project where the brief was wrong or missing.",
                    "A committed deadline is at risk and scope can't move — what did you actually do?",
                    "When has not knowing been fun?"),
            List.of(
                    "Needs certainty to act; freezes or escalates everything; discomfort reads as stress.",
                    "Copes when supported; waits for clarity rather than creating it.",
                    "Creates clarity for themselves and others; acts on 70% information; "
                            + "timeboxes instead of gold-plating.",
                    "Seeks ambiguity out; track record of turning unclear situations into plans "
                            + "others followed; energized by it."));

    private static final ScorecardGuidance FAGLIGHED = new ScorecardGuidance(
            "FAGLIGHED",
            "Faglighed & formidling",
            "Can they do the job at the level the role is hired for — and explain it simply?",
            "Can they do the job at the level the role is hired for — and explain it simply? "
                    + "Judged against the practice's kompetencekatalog (Aspirer baseline for grads "
                    + "→ Udfører for seniors), plus DNA Q4: complicated matters "
                    + "communicated simply — conclusions first, adapted to the listener. In the "
                    + "case round, score on the practice-owned case; in interviews without a case, "
                    + "depth-probe the CV: take their strongest claimed experience and test whether "
                    + "it survives three follow-ups (Why that trade-off? What broke first? What "
                    + "didn't you know at the time?).",
            List.of(
                    "The case — or a CV depth-probe on their strongest claimed experience.",
                    "Explain the core of your solution as if to the client's steering committee — two minutes.",
                    "Now explain it to the graduate joining Monday."),
            List.of(
                    "Below the catalog level the role requires; hand-waves where specifics are "
                            + "demanded; claims collapse under follow-up; loses the listener in complexity.",
                    "Fragments of the required level; name-drops frameworks rather than applying "
                            + "them; clear only with a script.",
                    "Meets the catalog level: applies competence, reasons about trade-offs, says "
                            + "clearly what they don't know — and presents it simply, conclusions first.",
                    "Above role level: reframes the problem, teaches the interviewer something, "
                            + "makes the complex feel obvious — steering-group-ready tomorrow."));

    private static final ScorecardGuidance COMMERCIAL_DRIVE = new ScorecardGuidance(
            "COMMERCIAL_DRIVE",
            "Commercial drive",
            "Curiosity about the client's business, and comfort talking value and "
                    + "price at their level.",
            "“Consultants first, commercially aware second.” Genuine curiosity about "
                    + "the client's business, spotting and routing extension opportunities, comfort "
                    + "talking value and price at their level — and realism about numbers (a "
                    + "confident “10 million” without asking about rates is a signal, not "
                    + "an answer).",
            List.of(
                    "On your last project, what should your firm have sold next — and why didn't it happen?",
                    "How do you keep a relationship alive after the project ends?",
                    "(Commercial roles) Give me a number you'd commit to, and the reasoning."),
            List.of(
                    "Sales is someone else's dirty job; no curiosity about the client's business; "
                            + "numbers are fantasy-grade.",
                    "Understands commercial context when pointed at it; spots opportunities but "
                            + "doesn't act or route them.",
                    "Genuinely curious about the client's business; spots and routes opportunities; "
                            + "comfortable on value/price at their level.",
                    "Track record of self-sales/extensions; thinks in client outcomes and Trustworks "
                            + "positioning unprompted; numbers are reasoned."));

    /**
     * The standard subjects in interview order. {@code CULTURE_FIT} is
     * resolvable but deliberately absent here — it is legacy, never offered
     * as a choice when building a new template.
     */
    private static final List<ScorecardGuidance> STANDARD = List.of(
            WHY_CONSULTING, CULTURE, SELF_LEADERSHIP, UNCERTAINTY, FAGLIGHED, COMMERCIAL_DRIVE);

    private static final Map<String, ScorecardGuidance> BY_CODE = index();

    private static Map<String, ScorecardGuidance> index() {
        Map<String, ScorecardGuidance> map = new LinkedHashMap<>();
        for (ScorecardGuidance guidance : STANDARD) {
            map.put(guidance.code(), guidance);
        }
        map.put(CULTURE_FIT.code(), CULTURE_FIT);
        return Map.copyOf(map);
    }

    /** @return the standard subjects in interview order — the offer list for new templates. */
    public static List<ScorecardGuidance> standard() {
        return STANDARD;
    }

    /** @return guidance for a code, empty for custom subjects the catalog knows nothing about. */
    public static Optional<ScorecardGuidance> forCode(String code) {
        return Optional.ofNullable(code).map(BY_CODE::get);
    }

    /** @return the attribute list a new position starts from — codes and labels only. */
    public static List<ScorecardAttribute> standardTemplate() {
        return STANDARD.stream()
                .map(g -> new ScorecardAttribute(g.code(), g.label(), null))
                .toList();
    }
}
