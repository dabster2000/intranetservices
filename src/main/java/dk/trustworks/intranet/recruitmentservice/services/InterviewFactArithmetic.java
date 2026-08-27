package dk.trustworks.intranet.recruitmentservice.services;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The room's date arithmetic (room spec 2026-08-26 §5.2, §5.4 — no model
 * involved): notice periods against start dates, checked while the
 * candidate is still in the chair. Pure functions, mirrored by the
 * frontend's {@code factArithmetic.ts} so both flag the same line.
 * <p>
 * Danish notice convention: "3 måneders opsigelse" almost always means
 * three months <em>to the end of a month</em> (løbende måned + 3), so the
 * earliest realistic start after resigning today is the first day of the
 * month {@code notice + 1} months out.
 */
public final class InterviewFactArithmetic {

    private InterviewFactArithmetic() {
    }

    /**
     * Months in a notice-period statement — "3 mdr", "3 måneder",
     * "3 months", "løbende måned + 3". Weeks ("2 uger", "2 weeks") are
     * rounded up to one month; a bare number is read as months. Empty when
     * nothing parseable is found.
     */
    private static final Pattern MONTHS = Pattern.compile(
            "(\\d{1,2})\\s*(?:mdr|md|måned|maaned|month|mo\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKS = Pattern.compile(
            "(\\d{1,2})\\s*(?:uge|week)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d{1,2})\\s*$");

    public static Optional<Integer> parseNoticeMonths(String noticeText) {
        if (noticeText == null || noticeText.isBlank()) {
            return Optional.empty();
        }
        String normalized = noticeText.toLowerCase(Locale.ROOT);
        Matcher months = MONTHS.matcher(normalized);
        if (months.find()) {
            return Optional.of(Integer.parseInt(months.group(1)));
        }
        Matcher weeks = WEEKS.matcher(normalized);
        if (weeks.find()) {
            return Optional.of(1); // any week count fits inside one month boundary
        }
        Matcher bare = BARE_NUMBER.matcher(normalized);
        if (bare.matches()) {
            return Optional.of(Integer.parseInt(bare.group(1)));
        }
        return Optional.empty();
    }

    /**
     * Earliest realistic start when resigning on {@code today} with
     * {@code noticeMonths} to the end of a month: the first day of the
     * month {@code noticeMonths + 1} months out.
     */
    public static LocalDate earliestStart(LocalDate today, int noticeMonths) {
        return today.withDayOfMonth(1).plusMonths(noticeMonths + 1L);
    }

    /**
     * The inline flag (§5.2): is {@code wantedStart} reachable given the
     * notice period? Empty = consistent or not decidable; present = the
     * one-line message the room shows in the margin.
     */
    public static Optional<String> startConflict(LocalDate today, String noticeText,
                                                 LocalDate wantedStart) {
        if (wantedStart == null) {
            return Optional.empty();
        }
        return parseNoticeMonths(noticeText)
                .map(months -> earliestStart(today, months))
                .filter(earliest -> wantedStart.isBefore(earliest))
                .map(earliest -> wantedStart + " is not reachable with " + noticeText.trim()
                        + " — earliest is " + earliest);
    }
}
