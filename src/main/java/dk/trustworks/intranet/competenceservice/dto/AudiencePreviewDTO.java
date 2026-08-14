package dk.trustworks.intranet.competenceservice.dto;

import java.util.List;

/**
 * The resolved audience of a requirement (spec §6.3, §11.4).
 *
 * <p>Shown live next to the targeting fields in the editor, so an empty or wrong audience is
 * visible <em>before</em> save rather than after go-live. An audience that reaches nobody is
 * the most dangerous state this module has: everything renders green, no cell is red, no
 * notification fires, and the module reports full compliance for a krav nobody was ever asked
 * to take. A headcount next to the fields is the cheapest possible defence against it.
 *
 * <p>Audience is evaluated live from team relationships and employment status and never
 * materialised, so this is a preview of the same computation the matrix will run — not a
 * separate estimate.
 *
 * @param headcount  people currently in the audience. Zero is a legitimate, deliberate state
 *                   (all three targets {@code []} parks a krav), which is exactly why it has
 *                   to be shown rather than assumed impossible.
 * @param people     who they are, name-resolved and ordered for display. The list is the check
 *                   that matters — "42 people" looks right far more often than the 42 names do.
 * @param unresolved targeting entries that did not resolve to an existing, active row. §11.4
 *                   requires an unknown uuid to be reported by name and never silently
 *                   no-matched: a typo'd practice uuid that quietly matches nobody produces the
 *                   green-and-empty failure above, and it is undetectable from the headcount
 *                   alone because a smaller-than-expected number looks like a targeting choice.
 */
public record AudiencePreviewDTO(int headcount,
                                 List<Person> people,
                                 List<Unresolved> unresolved) {

    public AudiencePreviewDTO {
        people = people == null ? List.of() : List.copyOf(people);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    /**
     * One person in the audience.
     *
     * <p>Name and uuid only. This is an authoring screen, not an HR one — nothing about
     * employment, salary or team belongs in a list whose only job is to let an author say
     * "yes, those are the people I meant".
     */
    public record Person(String useruuid, String name) {
    }

    /**
     * One targeting entry that resolved to nothing.
     *
     * @param targetType which array it came from: {@code practices}, {@code teams} or
     *                   {@code users}. Without it an author holding two similar uuid lists has
     *                   to search both to find the offender.
     * @param value      the uuid as supplied, echoed verbatim so it can be pasted into a search
     * @param reason     why, in one clause — unknown, inactive, or a rejected {@code UD}/null
     *                   practice token
     */
    public record Unresolved(String targetType, String value, String reason) {
    }
}
