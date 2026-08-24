package dk.trustworks.intranet.vacationservice.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NameNormalizerTest {

    @Test
    void trimsCollapsesAndLowercases() {
        assertEquals("camilla alm", NameNormalizer.normalize("Camilla  Alm"));
        assertEquals("lars østergaard", NameNormalizer.normalize("Lars  Østergaard "));
        assertEquals("sandra l.h. andersen", NameNormalizer.normalize("Sandra L.H. Andersen"));
        assertEquals("", NameNormalizer.normalize(null));
    }

    @Test
    void danishLettersAreNeverConflated() {
        assertNotEquals(NameNormalizer.normalize("Sørensen"), NameNormalizer.normalize("Sorensen"));
    }
}
