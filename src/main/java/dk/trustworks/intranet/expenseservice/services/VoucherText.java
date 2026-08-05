package dk.trustworks.intranet.expenseservice.services;

import java.util.regex.Pattern;

/**
 * Builds and parses the e-conomic voucher text for app-uploaded expenses:
 *
 * <pre>  Udlæg | {name ≤15 chars} | {category} #{uuid8}</pre>
 *
 * The trailing {@code #<uuid8>} marker is the expense's durable identity in
 * e-conomic: voucher NUMBERS are reassigned every time the accountant moves a
 * posting between kassekladder (verified live 2026-07-30, both directions),
 * but the entry text survives moves AND booking into the ledger. The nightly
 * sync uses the marker to re-link moved/renumbered vouchers automatically.
 *
 * The employee name is capped at {@value #MAX_NAME_LENGTH} characters and the
 * whole text at {@value #MAX_TEXT_LENGTH} (category truncated first) so the
 * marker can never be cut off by e-conomic's text-field limit.
 */
public final class VoucherText {

    static final int MAX_NAME_LENGTH = 15;
    static final int MAX_TEXT_LENGTH = 250; // e-conomic allows 255; small headroom
    private static final String PREFIX = "Udlæg | ";

    /** Marker anywhere in a text: {@code #} + 8 lowercase hex chars, word-bounded. */
    private static final Pattern MARKER_PATTERN = Pattern.compile("\\s*#[0-9a-f]{8}\\b");

    private VoucherText() {
    }

    /** {@code #a7c7bcab} for uuid {@code a7c7bcab-...}; empty when no usable uuid. */
    public static String markerFor(String expenseUuid) {
        if (expenseUuid == null || expenseUuid.length() < 8) return "";
        return "#" + expenseUuid.substring(0, 8).toLowerCase();
    }

    public static String build(String username, String accountName, String expenseUuid) {
        String name = username == null ? "" : username.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH).trim();
        }
        String category = accountName == null ? "" : accountName.trim();
        String marker = markerFor(expenseUuid);
        String suffix = marker.isEmpty() ? "" : " " + marker;

        String text = PREFIX + name + " | " + category + suffix;
        if (text.length() > MAX_TEXT_LENGTH) {
            // Never at the expense of the marker: shorten the category instead.
            int room = MAX_TEXT_LENGTH - (PREFIX + name + " | " + suffix).length();
            category = category.substring(0, Math.max(0, Math.min(category.length(), room))).trim();
            text = PREFIX + name + " | " + category + suffix;
        }
        return text;
    }

    /** True when {@code text} carries the marker of the given expense uuid. */
    public static boolean containsMarker(String text, String expenseUuid) {
        String marker = markerFor(expenseUuid);
        return !marker.isEmpty() && text != null && text.toLowerCase().contains(marker);
    }

    /**
     * Removes the marker from a text read back from e-conomic, so it never leaks
     * into {@code accountantNotes} / the intranet UI. Safe on texts without one.
     */
    public static String stripMarker(String text) {
        if (text == null) return null;
        return MARKER_PATTERN.matcher(text).replaceAll("").trim();
    }
}
