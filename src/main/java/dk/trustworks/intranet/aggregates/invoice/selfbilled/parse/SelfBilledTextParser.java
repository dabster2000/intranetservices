package dk.trustworks.intranet.aggregates.invoice.selfbilled.parse;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the per-consultant self-billed debtor-line text. Pure (no DB, no CDI).
 * Tolerant of the verified real variants: "Faktura:" / "faktura:" / "Faktrua:"
 * typo / "Faktura " (no colon), an optional leading space, and a faktura number
 * of any length. Correction lines ("Forkert ...", "Bogført ...") and anything
 * that doesn't match the shape return empty — they carry no code/period and are
 * netted via their voucher (Decision D10), never guessed.
 */
public final class SelfBilledTextParser {

    private SelfBilledTextParser() {}

    // ^ \s* fak<2-4 letters> :?  <faktura> - MM-YYYY <CODE> $
    private static final Pattern RX = Pattern.compile(
            "^\\s*fak[a-z]{2,4}:?\\s+(\\S+)\\s*-\\s*(\\d{2})-(\\d{4})\\s+([\\p{L}]+)\\s*$",
            Pattern.CASE_INSENSITIVE);

    public static Optional<ParsedLine> parse(String text) {
        if (text == null) return Optional.empty();
        Matcher m = RX.matcher(text);
        if (!m.matches()) return Optional.empty();
        int month = Integer.parseInt(m.group(2));
        int year = Integer.parseInt(m.group(3));
        if (month < 1 || month > 12) return Optional.empty();
        return Optional.of(new ParsedLine(m.group(1), year, month, m.group(4).toUpperCase()));
    }
}
