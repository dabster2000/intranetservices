package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EconomicsCustomerIndexTest {

    private EconomicsCustomerDto cust(int num, String name, String cvr) {
        EconomicsCustomerDto d = new EconomicsCustomerDto();
        d.setCustomerNumber(num);
        d.setName(name);
        d.setCvrNo(cvr);
        return d;
    }

    @Test
    void finds_by_cvr_when_unique() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                cust(1, "Alpha", "12345678"),
                cust(2, "Beta",  "87654321")
        ));
        assertEquals(Optional.of(1), idx.findByCvr("12345678"));
        assertEquals(Optional.of(2), idx.findByCvr("87654321"));
    }

    @Test
    void cvr_ambiguous_when_multiple_matches() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                cust(1, "Alpha",     "12345678"),
                cust(2, "Alpha DK",  "12345678")
        ));
        assertTrue(idx.isAmbiguousCvr("12345678"));
        assertTrue(idx.findByCvr("12345678").isEmpty());
    }

    @Test
    void cvr_no_match_when_missing() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(cust(1, "Alpha", "12345678")));
        assertTrue(idx.findByCvr("99999999").isEmpty());
        assertFalse(idx.isAmbiguousCvr("99999999"));
    }

    @Test
    void normalises_name_for_match_case_insensitive_and_whitespace_collapsed() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                cust(1, "Devoteam A/S",  "11111111"),
                cust(2, "Emagine DK",    "22222222")
        ));
        assertEquals(Optional.of(1), idx.findByName("devoteam a/s"));
        assertEquals(Optional.of(1), idx.findByName("  Devoteam   A/S  "));
    }

    @Test
    void name_ambiguous_when_multiple_normalised_matches() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                cust(1, "Emagine", "10001000"),
                cust(2, "EMAGINE", "10001000")
        ));
        assertTrue(idx.isAmbiguousName("emagine"));
        assertTrue(idx.findByName("emagine").isEmpty());
    }

    @Test
    void null_cvr_or_name_does_not_crash() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                cust(1, null, null)
        ));
        assertTrue(idx.findByCvr(null).isEmpty());
        assertTrue(idx.findByName(null).isEmpty());
    }

    @Test
    void strips_legal_suffixes_on_name_match() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                cust(1, "Devoteam A/S",  "11111111"),
                cust(2, "Contoso ApS",   "22222222"),
                cust(3, "Partners I/S",  "33333333"),
                cust(4, "Bravo K/S",     "44444444")
        ));
        // Suffixless queries resolve to suffixed names.
        assertEquals(Optional.of(1), idx.findByName("Devoteam"));
        assertEquals(Optional.of(2), idx.findByName("Contoso"));
        assertEquals(Optional.of(3), idx.findByName("Partners"));
        assertEquals(Optional.of(4), idx.findByName("Bravo"));
        // Suffixed queries still match.
        assertEquals(Optional.of(1), idx.findByName("Devoteam A/S"));
        assertEquals(Optional.of(2), idx.findByName("Contoso ApS"));
    }

    @Test
    void legal_suffix_stripping_is_case_insensitive() {
        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(List.of(
                cust(1, "Foo aps", "11111111")
        ));
        assertEquals(Optional.of(1), idx.findByName("foo APS"));
        assertEquals(Optional.of(1), idx.findByName("Foo"));
    }
}
