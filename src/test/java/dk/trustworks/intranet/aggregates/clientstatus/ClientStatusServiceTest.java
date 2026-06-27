package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCell;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusDetailResponse;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusInvoiceLine;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusResponse;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusRow;
import dk.trustworks.intranet.aggregates.clientstatus.services.ClientStatusService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class ClientStatusServiceTest {

    private static final LocalDate INVOICE_DATE = LocalDate.of(2099, 5, 15);
    private static final String MONTH_KEY = "209905";

    @Inject
    EntityManager em;

    @Inject
    ClientStatusService service;

    @Test
    @TestTransaction
    void selfBilledPhantoms_netInGridAndDetailViaBillingClient() {
        String clientUuid = uniqueUuid();
        String companyUuid = uniqueUuid();
        persistPhantom(companyUuid, clientUuid, 1000.0);
        persistPhantom(companyUuid, clientUuid, -250.0);
        em.flush();

        ClientStatusResponse grid = service.getClientStatus(YearMonth.from(INVOICE_DATE));
        ClientStatusRow row = grid.clients().stream()
                .filter(r -> r.clientUuid().equals(clientUuid))
                .findFirst()
                .orElseThrow();
        ClientStatusCell cell = row.cells().stream()
                .filter(c -> c.monthKey().equals(MONTH_KEY))
                .findFirst()
                .orElseThrow();
        assertEquals(750.0, cell.invoiced(), 0.001,
                "Client Status grid must keep signed PHANTOM amounts, including negative reversals");

        ClientStatusDetailResponse detail = service.getClientStatusDetail(
                clientUuid, INVOICE_DATE.getYear(), INVOICE_DATE.getMonthValue());
        assertEquals(750.0, detail.invoiced(), 0.001,
                "Detail headline must match the grid's PHANTOM billing basis");
        assertEquals(2, detail.invoices().size(), "Detail drawer should expose PHANTOM invoice rows");
        assertEquals(750.0, detail.invoices().stream()
                .mapToDouble(ClientStatusInvoiceLine::signedGrossConsultant)
                .sum(), 0.001);
    }

    private void persistPhantom(String companyUuid, String billingClientUuid, double amount) {
        String invoiceUuid = uniqueUuid();
        em.createNativeQuery("""
                INSERT INTO invoices (
                    uuid, type, status, invoicenumber, year, month, companyuuid,
                    billing_client_uuid, clientname, currency, invoicedate, duedate,
                    invoice_ref, vat, discount, internal_invoice_skip
                ) VALUES (
                    :uuid, 'PHANTOM', 'CREATED', 0, :year, :month, :company,
                    :client, '', 'DKK', :invoiceDate, :invoiceDate,
                    0, 0.0, 0.0, false
                )
                """)
                .setParameter("uuid", invoiceUuid)
                .setParameter("year", INVOICE_DATE.getYear())
                .setParameter("month", INVOICE_DATE.getMonthValue())
                .setParameter("company", companyUuid)
                .setParameter("client", billingClientUuid)
                .setParameter("invoiceDate", INVOICE_DATE)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO invoiceitems (
                    uuid, invoiceuuid, itemname, description, rate, hours, position,
                    origin, consultantuuid
                ) VALUES (
                    :uuid, :invoice, 'e-conomic auto-import', 'test phantom',
                    :rate, 1.0, 0, 'BASE', NULL
                )
                """)
                .setParameter("uuid", uniqueUuid())
                .setParameter("invoice", invoiceUuid)
                .setParameter("rate", amount)
                .executeUpdate();
    }

    private static String uniqueUuid() {
        String uuid = UUID.randomUUID().toString();
        assertNotNull(uuid);
        return uuid;
    }
}
