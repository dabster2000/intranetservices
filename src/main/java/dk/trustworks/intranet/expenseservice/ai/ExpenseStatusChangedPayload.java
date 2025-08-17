package dk.trustworks.intranet.expenseservice.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExpenseStatusChangedPayload {
    private String expenseId;
    private String previousStatus;
    private String newStatus;
    private String reason; // optional
    private String error;  // optional
    private String useruuid;
    private Integer voucherNumber;
    private Integer journalNumber;
    private String accountingYear;
    private String account;
    private Double amount;
    private String accountName;

    public static ExpenseStatusChangedPayload fromJson(String json) {
        try { return new ObjectMapper().readValue(json, ExpenseStatusChangedPayload.class);} 
        catch (Exception e) { throw new RuntimeException("Cannot parse payload", e);}    }
}
