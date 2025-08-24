# Invoice Process Overview

This document describes the end-to-end invoice process as implemented in the Trustworks Intranet application, based on the current source code. It focuses on user flows, data lifecycles, and system interactions without altering code.

Actors
- Account Manager / Sales: Reviews monthly client/project work, creates and edits draft invoices, submits final invoices, may create credit notes or phantom/internal invoices.
- Finance / Accounting: Books invoices in e-conomics; statuses reflected back via EconomicsInvoiceStatus.

Prerequisites
- Clients, Contracts, and Projects are configured.
- Work entries have been logged for the period.

High-level Flow
1) Select month and load invoice candidates
   - InvoiceView queries InvoiceService/InvoiceRestService for candidate projects and existing invoices for a selected month (loadProjectSummaryByYearAndMonth, getInvoicesForSingleMonth).
   - The UI groups data per Client and Project, showing Registered vs Invoiced amounts and highlighting users with not yet invoiced hours.

2) Create a draft invoice
   - From a candidate, the user creates a draft invoice via InvoiceService.createInvoiceFromProject (delegates to InvoiceRestService.createDraftFromProject).
   - Special flows:
     - Internal invoice drafts: createInternalInvoiceDraft(from company selection)
     - Internal service invoice drafts: createInternalServiceInvoiceDraft(from->to company, month)
     - Phantom invoices: createPhantomInvoice (used for proforma/placeholder purposes)

3) Edit draft invoice
   - The draft opens in a detail pane using the InvoiceDesign component with fields for addresses, attention, EAN, CVR, due/issue dates, currency, VAT, discount, and notes.
   - Line items are shown using InvoiceLineItem entries. Users can:
     - Add/edit/remove line items (description, hours, rate, consultant)
     - Apply discounts (percentage) and adjust VAT
     - Enter contract/project notes (persisted as InvoiceNote per contract+project+month)
   - Sums are updated live in the UI and computed using Invoice.getSumNoTax plus VAT and discount logic.
   - Changes to a draft are persisted via InvoiceService.update (REST: updateDraftInvoice).

4) Create final invoice / other actions
   - Create invoice: InvoiceService.createInvoice transitions a DRAFT to a CREATED invoice and triggers PDF generation server-side.
   - Credit note: InvoiceService.createCreditNote creates a CREDIT_NOTE invoice for a given original invoice.
   - Delete draft: InvoiceService.delete removes a DRAFT.
   - Regenerate PDF: InvoiceService.regeneratePdf requests a new PDF build for a given invoice.

5) Reference and bonus handling
   - Reference numbers and invoice references can be updated via InvoiceService.updateInvoiceReference.
   - Sales/bonus approval: Invoice.bonusConsultant, bonus amounts/override and SalesApprovalStatus are handled via InvoiceService.updateInvoiceBonusStatus.

6) Economics and payment lifecycle
   - EconomicsInvoiceStatus reflects integration status with e-conomics:
     - NA -> UPLOADED -> BOOKED -> PAID
   - Application-level InvoiceStatus tracks invoice lifecycle:
     - DRAFT -> CREATED -> SUBMITTED -> PAID or CANCELLED
     - CREDIT_NOTE indicates credit note invoices.

Data Sources and Key Interactions
- Work data (WorkService) provides registered hours used to compute candidate sums per project/consultant and to populate line items/rates.
- Project and Contract data (ProjectService, ContractService) define billable context and contract-related rules/discounts (e.g., SKI0217_2021 logic).
- Company data (CompanyRestService) provides issuer selection for internal invoices and address details on the invoice.
- REST API (InvoiceRestService) implements all persistence and server-side workflows for drafts, creation, deletion, notes, references, and PDF generation.

Notes Lifecycle
- Each contract+project+month can store an InvoiceNote; UI offers a dialog to edit and persist notes via InvoiceService.getInvoiceNote / updateInvoiceNote.

Invoice Types
- InvoiceType: INVOICE, CREDIT_NOTE, PHANTOM, INTERNAL, INTERNAL_SERVICE
- Drafts can be transitioned to different types through the flows above.

Error Handling
- InvoiceRestService gracefully logs and bubbles REST errors (e.g., draft creation failures) and may show UI notifications.

Related UI Components
- InvoiceView: Main controller/view orchestrating all actions.
- InvoiceDesign: Form section with fields and buttons for invoice operations.
- InvoiceLineItem: UI row for editing a line item.
- InvoiceClientItem / InvoiceClientDetailItem: Client/project grouping and detail selection.

See also
- data-model.md for field-level details
- services.md for available application services
- api.md for REST endpoints
- ../invoice_pagination.md for paging behaviour
