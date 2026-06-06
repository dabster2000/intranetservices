package dk.trustworks.intranet.aggregates.invoice.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class InternalInvoicePhantomLinkTest {

    @Test
    void constructor_stampsUuidAndCreatedAt_andCapturesAmount() {
        InternalInvoicePhantomLink link =
                new InternalInvoicePhantomLink("internal-1", "phantom-1", new BigDecimal("18750.00"));
        assertNotNull(link.uuid);
        assertFalse(link.uuid.isBlank());
        assertEquals("internal-1", link.internalUuid);
        assertEquals("phantom-1", link.phantomUuid);
        assertEquals(0, link.attributedAmountAtIssue.compareTo(new BigDecimal("18750.00")));
        assertNotNull(link.createdAt);
    }
}
