package dk.trustworks.intranet.competenceservice.dto;

/**
 * One answer option as a candidate is allowed to see it (spec §6.4, §10.2).
 *
 * <p><strong>Deliberately has no {@code correct} field at all.</strong> The stored
 * {@link dk.trustworks.intranet.competenceservice.content.CompetenceContent.Option} carries
 * the correctness flag and this record is a separate type rather than the same record with a
 * {@code @JsonIgnore} on one accessor. The distinction is the whole point: a
 * {@code @JsonIgnore} is one refactor, one {@code @JsonView} tweak or one "just serialise the
 * entity here" away from shipping the answer key to the browser, and it fails silently — the
 * test still passes, the page still renders, and every attempt taken after that moment is
 * worthless as evidence. A type that has no such field cannot leak it under any refactor,
 * because the compiler has no field to give away.
 *
 * <p>Since the module exists to produce evidence an auditor will trust, "the answers were
 * never sent to the client" has to be a structural property, not a convention. A contract
 * test asserts that no learner response body contains the string {@code "correct"}; this
 * record is what makes that test pass by construction rather than by vigilance.
 *
 * @param id   the stable payload option id — what a submission sends back
 * @param text the option text as authored
 */
public record LearnerOption(String id, String text) {
}
