package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceNote;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

@JBossLog
@ApplicationScoped
public class InvoiceNotesService {

    // Get invoice note by clientuuid, projectuuid and month
    public InvoiceNote getInvoiceNoteByClientProjectMonth(String contractuuid, String projectuuid, LocalDate month) {
        log.info("getInvoiceNoteByContractProjectMonth");
        return InvoiceNote.find("contractuuid = ?1 and projectuuid = ?2 and month = ?3", contractuuid, projectuuid, month).firstResult();
    }

    // Create new invoice note or update existing invoice note
    @Transactional
    public void createOrUpdateInvoiceNote(InvoiceNote invoiceNote) {
        log.info("createOrUpdateInvoiceNote");
        // Does the invoice note exist?
        boolean invoiceNoteExists = InvoiceNote.count("contractuuid = ?1 and projectuuid = ?2 and month = ?3", invoiceNote.getContractuuid(), invoiceNote.getProjectuuid(), invoiceNote.getMonth()) > 0;
        if (invoiceNoteExists) {
            // Update the invoice note
            InvoiceNote.update("note = ?1 where contractuuid = ?2 and projectuuid = ?3 and month = ?4", invoiceNote.getNote(), invoiceNote.getContractuuid(), invoiceNote.getProjectuuid(), invoiceNote.getMonth());
        } else {
            // Create the invoice note
            if(invoiceNote.getUuid() == null) {
                invoiceNote.setUuid(java.util.UUID.randomUUID().toString());
            }
            invoiceNote.persist();
        }
    }
}
