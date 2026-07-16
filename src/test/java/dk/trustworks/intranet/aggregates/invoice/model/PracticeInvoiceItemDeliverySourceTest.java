package dk.trustworks.intranet.aggregates.invoice.model;

import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PracticeInvoiceItemDeliverySourceTest {

    @Test
    void prePersistInitializesCreatedAtOnce() throws Exception {
        PracticeInvoiceItemDeliverySource source = new PracticeInvoiceItemDeliverySource();

        source.initializeCreatedAt();

        assertNotNull(source.createdAt);
        LocalDateTime initialized = source.createdAt;
        source.initializeCreatedAt();
        assertEquals(initialized, source.createdAt);
        assertNotNull(PracticeInvoiceItemDeliverySource.class
                .getDeclaredMethod("initializeCreatedAt")
                .getAnnotation(PrePersist.class));
    }

    @Test
    void createdAtIsRequiredAndImmutableAfterInsert() throws Exception {
        Column mapping = PracticeInvoiceItemDeliverySource.class
                .getField("createdAt")
                .getAnnotation(Column.class);

        assertNotNull(mapping);
        assertFalse(mapping.nullable());
        assertFalse(mapping.updatable());
    }
}
