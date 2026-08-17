package dk.trustworks.intranet.utils.json;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.time.LocalDateTime} member whose value is a UTC INSTANT — a real
 * moment stamped by the server or the database — so it serializes with an explicit
 * {@code Z} designator instead of the ambiguous bare shape that ECMAScript parses as
 * browser-local time.
 *
 * <p><b>Opt-in, deliberately.</b> An unannotated {@code LocalDateTime} keeps today's
 * behaviour. Annotating a member is a positive claim that must be verified against the
 * four-point checklist below, because the same wire shape also carries Europe/Copenhagen
 * WALL-CLOCK values entered by humans, and stamping those with {@code Z} shifts interview
 * times, candidate deadlines and news windows by the UTC offset — on pages external job
 * candidates read. A missed instant merely stays as wrong as it is today; a wrongly
 * annotated wall-clock value actively corrupts a time a human entered.
 *
 * <p>Do NOT annotate a member unless all four hold:
 * <ol>
 *   <li><b>Writer is a clock.</b> Every write site is {@code LocalDateTime.now()},
 *       {@code now(ZoneOffset.UTC)}, {@code now(Clock.systemUTC())} or a DB
 *       {@code CURRENT_TIMESTAMP}/{@code NOW()}/{@code UTC_TIMESTAMP()}. If ANY writer is a
 *       client-supplied {@code "date" + "T" + "time"} concat, a {@code LocalDate.atTime(...)}
 *       / {@code .atStartOfDay()} promotion, or arithmetic on one of those, it is WALL-CLOCK
 *       or a calendar-day marker — leave it alone. (Counter-examples in this codebase:
 *       {@code candidateDeadline} = {@code date.atTime(16, 0)}; {@code uploadedAt} =
 *       {@code file.getUploaddate().atStartOfDay()}; {@code evidence.expiresAt} =
 *       {@code coveredTo.atTime(23, 59, 59)}.)</li>
 *   <li><b>Not also rendered as text by the backend.</b> Grep the field for {@code .format(}
 *       into a Slack message, an email template extra, or an export column. If a backend
 *       formatter prints it naively, annotating makes the two surfaces disagree by the UTC
 *       offset. (Counter-example: {@code ConferenceParticipant.registered} rendered by
 *       {@code PhaseSlackNotifier}.)</li>
 *   <li><b>Not a token echoed back verbatim.</b> Concurrency tokens ({@code If-Match}),
 *       cursors and query params must be read through
 *       {@link dk.trustworks.intranet.utils.TemporalParams#parseUtcInstant} first, or the
 *       echoed {@code Z} breaks the reader.</li>
 *   <li><b>No UTC day-bucket filter feeds the same view.</b> If a list is filtered by
 *       {@code from.atStartOfDay()} … {@code to.plusDays(1).atStartOfDay()} (UTC days) and
 *       the rows are labelled with their day, moving the label to Copenhagen while the
 *       filter stays on UTC makes the two disagree for the 22:00–24:00 UTC band. Move the
 *       filter boundary in the same change, or do not annotate.</li>
 * </ol>
 *
 * <p>The inventory is pinned by {@code UtcInstantWireFormatTest} — adding an annotation is a
 * deliberate, test-visible act.
 */
@JacksonAnnotationsInside
@JsonSerialize(using = UtcInstantSerializer.class)
@JsonDeserialize(using = UtcInstantDeserializer.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
public @interface UtcInstant {
}
