package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the e-conomic voucher-text contract: 15-char name cap, the trailing
 * "#&lt;uuid8&gt;" identity marker that must NEVER be cut off (it is the sync's
 * only durable identity across the accountant's journal moves), and the strip
 * used on accountantNotes read-back.
 */
class VoucherTextTest {

    private static final String UUID = "a7c7bcab-a1ec-4717-a077-d8a87283a576";

    @Test
    void short_name_kept_and_marker_appended() {
        assertEquals("Udlæg | Trine Mikkelsen | Andet #a7c7bcab",
                VoucherText.build("Trine Mikkelsen ", "Andet", UUID));
    }

    @Test
    void long_name_is_capped_at_15_chars() {
        assertEquals("Udlæg | Christian Helle | Internet #a7c7bcab",
                VoucherText.build("Christian Heller Larsen", "Internet", UUID));
    }

    @Test
    void null_name_and_category_are_tolerated() {
        assertEquals("Udlæg |  |  #a7c7bcab", VoucherText.build(null, null, UUID));
    }

    @Test
    void very_long_category_is_truncated_but_marker_survives() {
        String category = "x".repeat(400);
        String text = VoucherText.build("Trine Mikkelsen", category, UUID);
        assertTrue(text.length() <= VoucherText.MAX_TEXT_LENGTH, "text must stay within the e-conomic limit");
        assertTrue(text.endsWith(" #a7c7bcab"), "marker must never be cut off");
    }

    @Test
    void marker_for_uuid_is_first_8_hex_chars() {
        assertEquals("#a7c7bcab", VoucherText.markerFor(UUID));
        assertEquals("", VoucherText.markerFor(null));
    }

    @Test
    void contains_marker_matches_case_insensitively_and_only_own_marker() {
        assertTrue(VoucherText.containsMarker("Udlæg | X | Y #a7c7bcab", UUID));
        assertTrue(VoucherText.containsMarker("UDLÆG | X | Y #A7C7BCAB", UUID));
        assertFalse(VoucherText.containsMarker("Udlæg | X | Y #deadbeef", UUID));
        assertFalse(VoucherText.containsMarker(null, UUID));
    }

    @Test
    void strip_removes_marker_wherever_it_sits_and_is_safe_without_one() {
        assertEquals("Udlæg | Trine Mikkelsen | Andet",
                VoucherText.stripMarker("Udlæg | Trine Mikkelsen | Andet #a7c7bcab"));
        assertEquals("Udlæg | X | Y betalt 5/8",
                VoucherText.stripMarker("Udlæg | X | Y #a7c7bcab betalt 5/8"));
        assertEquals("flyttet af revisor", VoucherText.stripMarker("flyttet af revisor"));
        assertEquals("", VoucherText.stripMarker("  #a7c7bcab  "));
    }
}
