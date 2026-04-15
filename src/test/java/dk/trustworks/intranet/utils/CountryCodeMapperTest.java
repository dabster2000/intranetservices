package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CountryCodeMapperTest {

    @Test
    void maps_known_iso_codes_to_economics_names() {
        assertEquals("Denmark",         CountryCodeMapper.toEconomicsName("DK"));
        assertEquals("Norway",          CountryCodeMapper.toEconomicsName("NO"));
        assertEquals("United Kingdom",  CountryCodeMapper.toEconomicsName("GB"));
        assertEquals("Sweden",          CountryCodeMapper.toEconomicsName("SE"));
        assertEquals("United States",   CountryCodeMapper.toEconomicsName("US"));
        assertEquals("Germany",         CountryCodeMapper.toEconomicsName("DE"));
    }

    @Test
    void maps_lowercase_codes() {
        assertEquals("Denmark", CountryCodeMapper.toEconomicsName("dk"));
    }

    @Test
    void falls_back_to_locale_for_uncovered_iso_code() {
        // "FR" is not in our overrides table; verify Locale.getDisplayCountry returns a sensible value
        String france = CountryCodeMapper.toEconomicsName("FR");
        assertEquals("France", france);
    }

    @Test
    void throws_on_null_or_blank() {
        assertThrows(IllegalArgumentException.class, () -> CountryCodeMapper.toEconomicsName(null));
        assertThrows(IllegalArgumentException.class, () -> CountryCodeMapper.toEconomicsName(""));
        assertThrows(IllegalArgumentException.class, () -> CountryCodeMapper.toEconomicsName("  "));
    }

    @Test
    void throws_on_unknown_code() {
        // ZZ is not a valid ISO 3166-1 alpha-2 code; Locale returns the code itself which we treat as failure.
        assertThrows(IllegalArgumentException.class, () -> CountryCodeMapper.toEconomicsName("ZZ"));
    }
}
