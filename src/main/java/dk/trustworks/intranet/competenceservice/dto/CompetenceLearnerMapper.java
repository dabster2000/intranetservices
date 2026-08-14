package dk.trustworks.intranet.competenceservice.dto;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place a stored test payload becomes something a learner may see.
 *
 * <p>Every path that hands questions to a candidate — starting an attempt, resuming one —
 * goes through {@link #questions}. That matters more than it looks: the guarantee of spec
 * §6.4 is "no correctness flag ever reaches the browser", and a guarantee held by a single
 * function is checkable, while one held by three call sites that each build their own list is
 * only ever probably true. The stored {@code Option} record and the wire {@link LearnerOption}
 * record are different types, so a future caller that tries to shortcut this mapper does not
 * compile rather than quietly leaking.
 *
 * <h2>Why the shuffle is applied here and not in the browser</h2>
 *
 * <p>The option order is part of the attempt, not part of the rendering. It is decided once
 * when the attempt is started, written to {@code competence_attempt.option_order_json}, and
 * replayed on every read of that attempt. Three things follow, and none of them is available
 * to a client-side shuffle:
 *
 * <ul>
 *   <li><strong>Resume is stable.</strong> A candidate who reloads the page, loses their
 *       connection or comes back after lunch sees the options exactly where they left them.
 *       A browser-side shuffle re-randomises on every mount, so answers move under the
 *       person mid-test — which reads as the system cheating, and is unanswerable in a
 *       support conversation because nobody can reconstruct what was on screen.</li>
 *   <li><strong>The order is evidence.</strong> The row records what this candidate was
 *       shown. "Which options did question 4 present, in which order, on that date" is a
 *       question an auditor can ask, and only a server-recorded order can answer it.</li>
 *   <li><strong>It is not a security control, and pretending otherwise would be worse.</strong>
 *       Shuffling defends against shoulder-surfing and shared screenshots, not against
 *       tampering — the actual control is that no correctness flag is sent and that scoring
 *       happens server-side against the frozen payload (§6.4, §10.2). A client-side shuffle
 *       would look like a control while being trivially reversible, which is the most
 *       expensive kind of security theatre: it invites people to stop asking about the real
 *       control.</li>
 * </ul>
 *
 * <p>Question order is deliberately <em>not</em> shuffled (see
 * {@code CompetenceAttemptService.shuffleOptions}).
 */
public final class CompetenceLearnerMapper {

    private CompetenceLearnerMapper() {
    }

    /**
     * The frozen payload as this attempt must display it.
     *
     * <p>Questions come out in payload order. Each question's options come out in the order
     * recorded for it in {@code optionOrder}, falling back to payload order when the map has
     * no entry for that question — which happens for an attempt written before the shuffle
     * existed, and whenever {@code CompetenceAttemptService.optionOrder} could not read the
     * stored JSON and returned an empty map rather than failing the read. Falling back is
     * right: an unreadable shuffle should cost the candidate a differently-ordered list, never
     * an error page in the middle of a test.
     *
     * <p>The recorded order is treated as a <em>preference</em>, not as the source of truth
     * for which options exist. Ids in the record that are not in the payload are skipped, and
     * payload options the record does not mention are appended in payload order. Neither can
     * happen while the version an attempt points at is immutable, but the alternative to
     * handling it is a candidate silently sitting a test with a missing or phantom option —
     * and a missing option can be the correct one, which turns a content accident into a
     * wrongly failed person.
     *
     * @param payload     the version frozen onto the attempt — never the current ACTIVE one
     * @param optionOrder questionId → option ids in display order; empty or {@code null}
     *                    means "use payload order"
     * @return questions carrying {@link LearnerOption}s, which have no correctness flag
     */
    public static List<LearnerQuestion> questions(CompetenceContent.TestPayload payload,
                                                  Map<String, List<String>> optionOrder) {
        if (payload == null) {
            return List.of();
        }
        Map<String, List<String>> order = optionOrder == null ? Map.of() : optionOrder;

        List<LearnerQuestion> out = new ArrayList<>(payload.questions().size());
        for (CompetenceContent.Question question : payload.questions()) {
            out.add(new LearnerQuestion(
                    question.id(),
                    question.text(),
                    options(question, order.get(question.id()))));
        }
        return out;
    }

    /**
     * One question's options, in the recorded order where there is one.
     *
     * <p>Deliberately builds {@link LearnerOption} instances field by field rather than
     * copying the stored record: this is the line that drops {@code correct}, and it should be
     * visible as such.
     */
    private static List<LearnerOption> options(CompetenceContent.Question question,
                                               List<String> recordedOrder) {
        List<CompetenceContent.Option> source = question.options();
        if (recordedOrder == null || recordedOrder.isEmpty()) {
            return source.stream().map(o -> new LearnerOption(o.id(), o.text())).toList();
        }

        List<LearnerOption> out = new ArrayList<>(source.size());
        Set<String> placed = new LinkedHashSet<>();
        for (String optionId : recordedOrder) {
            CompetenceContent.Option option = byId(source, optionId);
            // Unknown id, or the same id recorded twice: skip. Emitting it twice would let a
            // candidate answer one question with an id the scorer sees once.
            if (option != null && placed.add(option.id())) {
                out.add(new LearnerOption(option.id(), option.text()));
            }
        }
        for (CompetenceContent.Option option : source) {
            if (placed.add(option.id())) {
                out.add(new LearnerOption(option.id(), option.text()));
            }
        }
        return out;
    }

    private static CompetenceContent.Option byId(List<CompetenceContent.Option> options, String id) {
        if (id == null) {
            return null;
        }
        for (CompetenceContent.Option option : options) {
            if (id.equals(option.id())) {
                return option;
            }
        }
        return null;
    }
}
