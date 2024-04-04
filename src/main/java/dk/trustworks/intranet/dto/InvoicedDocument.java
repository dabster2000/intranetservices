package dk.trustworks.intranet.dto;


import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;

import java.time.LocalDate;


public class InvoicedDocument {

    private final int invoicenumber;
    private final InvoiceType invoiceType;
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private final LocalDate month;
    private final double invoiced;

    public InvoicedDocument(int invoicenumber, InvoiceType invoiceType, LocalDate month, double invoiced) {
        this.invoicenumber = invoicenumber;
        this.invoiceType = invoiceType;
        this.month = month;
        this.invoiced = invoiced;
    }

    public InvoiceType getInvoiceType() {
        return invoiceType;
    }

    public LocalDate getMonth() {
        return month;
    }

    public double getInvoiced() {
        return invoiced;
    }

    @Override
    public String toString() {
        return "InvoicedDocument{" +
                "invoicenumber=" + invoicenumber +
                ", invoiceType=" + invoiceType +
                ", month=" + month +
                ", invoiced=" + invoiced +
                '}';
    }
}
