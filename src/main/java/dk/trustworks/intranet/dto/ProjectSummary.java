package dk.trustworks.intranet.dto;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dto.enums.ProjectSummaryType;
import dk.trustworks.intranet.invoiceservice.model.Invoice;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ProjectSummary {

    private ProjectSummaryType projectSummaryType;
    private String contractuuid;
    private String projectuuid;
    private String projectname;
    private Client client;
    private String clientname;
    private String description;
    private double registeredamount;
    private double invoicedamount;
    private int invoices;
    private List<Invoice> invoiceList = new ArrayList<>();
    private List<Invoice> draftInvoiceList = new ArrayList<>();

    public ProjectSummary(String contractuuid, String projectuuid, String projectname, Client client, String clientname, String description, double registeredamount, double invoicedamount, int invoices, ProjectSummaryType projectSummaryType) {
        this.contractuuid = contractuuid;
        this.projectuuid = projectuuid;
        this.projectname = projectname;
        this.client = client;
        this.clientname = clientname;
        this.description = description;
        this.registeredamount = registeredamount;
        this.invoicedamount = invoicedamount;
        this.invoices = invoices;
        this.projectSummaryType = projectSummaryType;
    }

    public void addAmount(double amount) {
        registeredamount += amount;
    }
}
