package dk.trustworks.intranet.aggregates.invoice.economics.book;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter; import lombok.Setter;

@Getter @Setter
@JsonInclude(JsonInclude.Include.NON_NULL)  // omit sendBy when null (SPEC §6.6)
public class EconomicsBookingRequest {
    private Draft draftInvoice;
    private String sendBy;   // null | "ean" | "Email"

    @Getter @Setter
    public static class Draft {
        private int draftInvoiceNumber;
    }

    public static EconomicsBookingRequest of(int draftNumber, String sendBy) {
        EconomicsBookingRequest r = new EconomicsBookingRequest();
        Draft d = new Draft(); d.setDraftInvoiceNumber(draftNumber);
        r.setDraftInvoice(d);
        r.setSendBy(sendBy);  // null → serialisation omits the field
        return r;
    }
}
