package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceNote;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

@JBossLog
@ApplicationScoped
public class InvoiceNotesService {

    public InvoiceNote getInvoiceNoteByClientProjectMonth(String contractuuid, String projectuuid, LocalDate month) {
        log.debugf("getInvoiceNoteByContractProjectMonth: contractUuid=%s, projectUuid=%s, month=%s",
                contractuuid, projectuuid, month);
        try {
            InvoiceNote result = InvoiceNote.find("contractuuid = ?1 and projectuuid = ?2 and month = ?3",
                    contractuuid, projectuuid, month).firstResult();
            log.debugf("getInvoiceNoteByContractProjectMonth: contractUuid=%s, found=%s", contractuuid, result != null);
            return result;
        } catch (Exception e) {
            log.errorf(e, "Error fetching invoice note: contractUuid=%s, projectUuid=%s, month=%s",
                    contractuuid, projectuuid, month);
            throw e;
        }
    }

    @Transactional
    public void createOrUpdateInvoiceNote(InvoiceNote invoiceNote) {
        log.debugf("createOrUpdateInvoiceNote: contractUuid=%s, projectUuid=%s, month=%s",
                invoiceNote.getContractuuid(), invoiceNote.getProjectuuid(), invoiceNote.getMonth());
        try {
            boolean invoiceNoteExists = InvoiceNote.count("contractuuid = ?1 and projectuuid = ?2 and month = ?3",
                    invoiceNote.getContractuuid(), invoiceNote.getProjectuuid(), invoiceNote.getMonth()) > 0;
            if (invoiceNoteExists) {
                InvoiceNote.update("note = ?1 where contractuuid = ?2 and projectuuid = ?3 and month = ?4",
                        invoiceNote.getNote(), invoiceNote.getContractuuid(), invoiceNote.getProjectuuid(), invoiceNote.getMonth());
                log.infof("Invoice note updated: contractUuid=%s, projectUuid=%s, month=%s",
                        invoiceNote.getContractuuid(), invoiceNote.getProjectuuid(), invoiceNote.getMonth());
            } else {
                if (invoiceNote.getUuid() == null) {
                    invoiceNote.setUuid(java.util.UUID.randomUUID().toString());
                }
                invoiceNote.persist();
                log.infof("Invoice note created: uuid=%s, contractUuid=%s, projectUuid=%s, month=%s",
                        invoiceNote.getUuid(), invoiceNote.getContractuuid(), invoiceNote.getProjectuuid(), invoiceNote.getMonth());
            }
        } catch (Exception e) {
            log.errorf(e, "Error creating/updating invoice note: contractUuid=%s, projectUuid=%s, month=%s",
                    invoiceNote.getContractuuid(), invoiceNote.getProjectuuid(), invoiceNote.getMonth());
            throw e;
        }
    }
}
