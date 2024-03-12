package dk.trustworks.intranet.financeservice.model;

import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import java.util.List;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "integration_keys")
public class IntegrationKey extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;
    private String key;
    private String value;

    public static IntegrationKeyValue getIntegrationKeyValue(Company company) {
        List<IntegrationKey> integrationKeys = IntegrationKey.<IntegrationKey>find("company = ?1", company).list();
        String url = integrationKeys.stream().filter(i -> i.getKey().equals("url")).findFirst().orElse(new IntegrationKey()).getValue();
        String appSecretToken = integrationKeys.stream().filter(i -> i.getKey().equals("X-AppSecretToken")).findFirst().orElse(new IntegrationKey()).getValue();
        String agreementGrantToken = integrationKeys.stream().filter(i -> i.getKey().equals("X-AgreementGrantToken")).findFirst().orElse(new IntegrationKey()).getValue();
        int expenseJournalNumber = Integer.parseInt(integrationKeys.stream().filter(i -> i.getKey().equals("expense-journal-number")).findFirst().orElse(new IntegrationKey()).getValue());
        int invoiceJournalNumber = Integer.parseInt(integrationKeys.stream().filter(i -> i.getKey().equals("invoice-journal-number")).findFirst().orElse(new IntegrationKey()).getValue());
        int invoiceAccountNumber = Integer.parseInt(integrationKeys.stream().filter(i -> i.getKey().equals("invoice-account-number")).findFirst().orElse(new IntegrationKey()).getValue());
        IntegrationKeyValue result = new IntegrationKeyValue(url, appSecretToken, agreementGrantToken, expenseJournalNumber, invoiceJournalNumber, invoiceAccountNumber);
        return result;
    }

    public record IntegrationKeyValue(String url, String appSecretToken, String agreementGrantToken, int expenseJournalNumber, int invoiceJournalNumber, int invoiceAccountNumber) {
    }
}
