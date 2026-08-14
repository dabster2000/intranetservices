package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of {@code POST /admin/requirements/{uuid}/{kind}/publish} (spec §6.3, §9.4).
 *
 * <p>Both flags are {@code Boolean} rather than {@code boolean} so that "absent" can be told
 * from "false" and given the right default. A primitive would default an omitted
 * {@code forcedRetake} to {@code false}, which is the dangerous direction: a real content
 * change would go live without asking anybody to re-read it, and every cell would stay green
 * over content the audience has never seen. The compact constructor pins the defaults once, so
 * callers can unbox both accessors safely.
 *
 * @param forcedRetake          defaults to {@code true} — a publish is assumed to be a real
 *                              change. {@code false} is the typo-fix path: it carries the
 *                              previous version's completions forward with their original
 *                              timestamps, so correcting a comma does not reset the whole
 *                              audience's cadence clock. It has no effect on a TEST, where
 *                              attempts are immutable and everyone re-sits.
 * @param acknowledgeUnresolved defaults to {@code false}. Publishing content that still
 *                              carries {@code [Udfyldes]} authoring markers is refused with a
 *                              {@code 409} listing them; passing {@code true} means somebody
 *                              has read that list and wants it live anyway. The default has to
 *                              be the refusing one — shipping placeholder text to an auditor
 *                              would undermine the exact claim this module exists to support.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublishRequest(Boolean forcedRetake, Boolean acknowledgeUnresolved) {

    public PublishRequest {
        forcedRetake = forcedRetake == null ? Boolean.TRUE : forcedRetake;
        acknowledgeUnresolved = acknowledgeUnresolved == null ? Boolean.FALSE : acknowledgeUnresolved;
    }

    /**
     * The defaults for a request with no body at all.
     *
     * <p>{@code POST} with an empty body binds to {@code null}, and the resource must not
     * dereference it. Force-retake on, acknowledgement off — the safe pair.
     */
    public static PublishRequest orDefaults(PublishRequest body) {
        return body == null ? new PublishRequest(null, null) : body;
    }
}
