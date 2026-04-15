package dk.trustworks.intranet.aggregates.invoice.economics.book;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter; import lombok.Setter;

import java.time.LocalDate;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsBookedInvoice {
    private Integer bookedInvoiceNumber;
    private LocalDate date;
    private LocalDate dueDate;
    private Double grossAmount;
    private Double remainder;            // 0 when paid
    private String paymentStatus;        // e.g. "unpaid", "paid"
}
