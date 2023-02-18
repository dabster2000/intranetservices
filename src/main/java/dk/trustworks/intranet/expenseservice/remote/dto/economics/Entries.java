package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "financeVouchers"
})
public class Entries {

    @JsonProperty("financeVouchers")
    public List<FinanceVoucher> financeVouchers = null;

    @JsonProperty("manualCustomerInvoices")
    public List<ManualCustomerInvoice> manualCustomerInvoices = null;


    public Entries(){
    }

    public Entries(List<FinanceVoucher> financeVouchers){
        this.financeVouchers = financeVouchers;
    }

    @JsonProperty("financeVouchers")
    public List<FinanceVoucher> getFinanceVouchers() {
        return financeVouchers;
    }

    @JsonProperty("financeVouchers")
    public void setFinanceVouchers(List<FinanceVoucher> financeVouchers) {
        this.financeVouchers = financeVouchers;
    }

    @JsonProperty("manualCustomerInvoices")
    public List<ManualCustomerInvoice> getManualCustomerInvoices() {
        return manualCustomerInvoices;
    }

    @JsonProperty("manualCustomerInvoices")
    public void setManualCustomerInvoices(List<ManualCustomerInvoice> manualCustomerInvoices) {
        this.manualCustomerInvoices = manualCustomerInvoices;
    }


    @Override
    public String toString() {
        return "Entries{" +
                "financeVouchers=" + StringUtils.join(financeVouchers, " | ") +
                '}';
    }
}