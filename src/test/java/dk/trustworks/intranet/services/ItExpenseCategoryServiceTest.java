package dk.trustworks.intranet.services;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The validation rules behind the Settings → IT Budget equipment-type editor.
 * Static package-visible helpers, so this runs in the DB-free fast tier.
 */
class ItExpenseCategoryServiceTest {

    @Nested
    @DisplayName("lifespan (amortization length, months)")
    class Lifespan {

        @Test
        void acceptsTheLengthsInUseToday() {
            // 24 = phone, 36 = laptop, 48 = non-tech — the live itbudget_category rows.
            assertEquals(24, ItExpenseCategoryService.requireLifespan(24));
            assertEquals(36, ItExpenseCategoryService.requireLifespan(36));
            assertEquals(48, ItExpenseCategoryService.requireLifespan(48));
        }

        @Test
        void acceptsTheBounds() {
            assertEquals(1, ItExpenseCategoryService.requireLifespan(ItExpenseCategoryService.LIFESPAN_MIN));
            assertEquals(120, ItExpenseCategoryService.requireLifespan(ItExpenseCategoryService.LIFESPAN_MAX));
        }

        @Test
        void rejectsZero() {
            // A missing lifespan arrives as 0 from the resource's Integer -> int
            // fallback; 0 would amortize an item the instant it is registered.
            assertThrows(BadRequestException.class, () -> ItExpenseCategoryService.requireLifespan(0));
        }

        @Test
        void rejectsNegative() {
            assertThrows(BadRequestException.class, () -> ItExpenseCategoryService.requireLifespan(-1));
        }

        @Test
        void rejectsAbsurdlyLongLength() {
            // A mistyped 3600 would pin the employee's budget for 300 years.
            assertThrows(BadRequestException.class, () -> ItExpenseCategoryService.requireLifespan(3600));
        }
    }

    @Nested
    @DisplayName("name")
    class Name {

        @Test
        void trimsSurroundingWhitespace() {
            assertEquals("laptop", ItExpenseCategoryService.requireName("  laptop  "));
        }

        @Test
        void rejectsNull() {
            assertThrows(BadRequestException.class, () -> ItExpenseCategoryService.requireName(null));
        }

        @Test
        void rejectsBlank() {
            assertThrows(BadRequestException.class, () -> ItExpenseCategoryService.requireName("   "));
        }

        @Test
        void rejectsOverTheColumnWidth() {
            // name is varchar(25): MariaDB would reject or cut the value short far
            // from the caller, so the service refuses it up front.
            String tooLong = "x".repeat(ItExpenseCategoryService.NAME_MAX + 1);
            assertThrows(BadRequestException.class, () -> ItExpenseCategoryService.requireName(tooLong));
        }

        @Test
        void acceptsExactlyTheColumnWidth() {
            String exact = "x".repeat(ItExpenseCategoryService.NAME_MAX);
            assertEquals(exact, ItExpenseCategoryService.requireName(exact));
        }
    }

    @Nested
    @DisplayName("optional text (longName, description)")
    class OptionalText {

        @Test
        void nullsOutAnEmptyValue() {
            assertNull(ItExpenseCategoryService.optionalText("   ", 100, "longName"));
            assertNull(ItExpenseCategoryService.optionalText(null, 100, "longName"));
        }

        @Test
        void trimsWhatItKeeps() {
            assertEquals("Laptop", ItExpenseCategoryService.optionalText(" Laptop ", 100, "longName"));
        }

        @Test
        void rejectsOverTheColumnWidth() {
            String tooLong = "x".repeat(ItExpenseCategoryService.DESCRIPTION_MAX + 1);
            assertThrows(BadRequestException.class,
                    () -> ItExpenseCategoryService.optionalText(tooLong, ItExpenseCategoryService.DESCRIPTION_MAX, "description"));
        }
    }
}
