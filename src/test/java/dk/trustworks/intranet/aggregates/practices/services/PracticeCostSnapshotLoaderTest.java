package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Seam tests for the legacy vs certified cost aggregate loaders. The legacy path restores the
 * pre-extraction {@code NULL -> 0} contract for all-NULL SQL aggregates; the certified path must
 * keep a missing aggregate null so suppressible metrics stay unavailable.
 */
class PracticeCostSnapshotLoaderTest {

    private static final IntSupplier TIMEOUT = () -> 15_000;

    @Test
    void legacyNullOpexAggregateIsZeroFilledBeforeDecimalConversion() {
        // MariaDB returns NULL for SUM(opex_amount_dkk) on an all-NULL group. Before the fix the
        // shared decimal() helper evaluated new BigDecimal("null") and threw.
        PracticeCostSnapshotLoader loader = loaderReturning(List.of(
                costRow("PM", "202605", "SALARIES", new BigDecimal("1200.00")),
                costRow("PM", "202606", "OPEX", null)));

        List<Object[]> rows = invoke(loader, "loadCostRows",
                new Class<?>[]{String.class, String.class, Set.class, IntSupplier.class},
                "202507", "202606", Set.of("BOOKED"), TIMEOUT);

        assertEquals(new BigDecimal("1200.00"), rows.get(0)[4], "ordinary amounts pass through unchanged");
        assertEquals(0, BigDecimal.ZERO.compareTo(convert(rows.get(1)[4])),
                "a null legacy OPEX aggregate becomes zero, never a thrown conversion");
    }

    @Test
    void legacyNullFteAggregateIsZeroFilledBeforeDecimalConversion() {
        PracticeCostSnapshotLoader loader = loaderReturning(List.of(
                fteRow("PM", "202605", new BigDecimal("3.500000")),
                fteRow("PM", "202606", null)));

        List<Object[]> rows = invoke(loader, "loadFteRows",
                new Class<?>[]{String.class, String.class, IntSupplier.class},
                "202507", "202606", TIMEOUT);

        assertEquals(new BigDecimal("3.500000"), rows.get(0)[3], "ordinary FTE passes through unchanged");
        assertEquals(0, BigDecimal.ZERO.compareTo(convert(rows.get(1)[3])),
                "a null legacy FTE aggregate becomes zero, never a thrown conversion");
    }

    @Test
    void certifiedCostAndFteGenerationNullAggregatesRemainNullNotZeroFilled() {
        // The legacy zero-fill must not leak to the certified generation loaders: a missing
        // canonical aggregate must remain null so suppressible cost/FTE metrics stay unavailable.
        PracticeCostSnapshotLoader costLoader = loaderReturning(Collections.singletonList(
                costRow("PM", "202606", "OPEX", null)));
        List<Object[]> costRows = invoke(costLoader, "loadCanonicalCostRows",
                new Class<?>[]{String.class, String.class, String.class, Set.class, IntSupplier.class},
                "basis", "202507", "202606", Set.of("BOOKED"), TIMEOUT);
        assertNull(costRows.get(0)[4], "certified cost aggregate is not coerced to zero");

        PracticeCostSnapshotLoader fteLoader = loaderReturning(Collections.singletonList(
                fteRow("PM", "202606", null)));
        List<Object[]> fteRows = invoke(fteLoader, "loadCanonicalFteRows",
                new Class<?>[]{String.class, String.class, String.class, IntSupplier.class},
                "basis", "202507", "202606", TIMEOUT);
        assertNull(fteRows.get(0)[3], "certified FTE aggregate is not coerced to zero");
    }

    private static BigDecimal convert(Object value) {
        return invokeStatic("decimal", new Class<?>[]{Object.class}, value);
    }

    private static PracticeCostSnapshotLoader loaderReturning(List<Object[]> rows) {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(copy(rows));
        PracticeCostSnapshotLoader loader = new PracticeCostSnapshotLoader();
        loader.em = em;
        return loader;
    }

    private static List<Object[]> copy(List<Object[]> rows) {
        return rows.stream().map(row -> Arrays.copyOf(row, row.length)).toList();
    }

    private static Object[] costRow(String practice, String monthKey, String costType, BigDecimal amount) {
        return new Object[]{"company", practice, monthKey, costType, amount};
    }

    private static Object[] fteRow(String practice, String monthKey, BigDecimal monthlyFte) {
        return new Object[]{"company", practice, monthKey, monthlyFte};
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T> T invokeStatic(String name, Class<?>[] parameterTypes, Object... arguments) {
        return invoke(new PracticeCostSnapshotLoader(), name, parameterTypes, arguments);
    }
}
