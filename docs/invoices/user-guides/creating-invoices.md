# Creating Invoices - User Guide

## Overview

This guide walks you through creating invoices in the Trustworks system, from initial draft to final submission.

## Prerequisites

- SALES role or higher
- Active project with registered work
- Client information in the system

## Step-by-Step Process

### 1. Navigate to Invoice View

1. Log in to Trustworks Intranet
2. Navigate to **Sales** → **Invoices** in the main menu
3. The Invoice View opens showing the current month

### 2. Select Time Period

Use the month navigation controls:
- **Previous Month**: Click the left arrow
- **Next Month**: Click the right arrow
- **Jump to Month**: Click the month/year display to open date picker

### 3. Find Your Project

The view displays projects grouped by client in an accordion layout:

1. **Look for the client name** in the accordion headers
2. **Click to expand** the client section
3. **Find your project** in the list
4. Check the **registered amount** to ensure work is recorded

### 4. Create Draft Invoice

#### From Project Work

1. **Click "New draft"** button next to your project
2. System creates a draft with:
   - Client information from contract
   - Work items from selected month
   - Default payment terms
   - Status: DRAFT

#### Manual Creation

1. Click **"Create Manual Invoice"** button
2. Fill in required fields:
   - Client name and address
   - Invoice date
   - Due date (typically +30 days)
   - Currency (DKK, EUR, SEK, USD, GBP)
3. Click **"Create Draft"**

### 5. Edit Draft Invoice

#### Add/Edit Line Items

1. **Add Line Item**: Click "+" button
2. Fill in:
   - **Item Name**: Service description
   - **Consultant**: Select from dropdown
   - **Rate**: Hourly rate
   - **Hours**: Number of hours
   - **Description**: Detailed description (optional)
3. **Reorder Items**: Drag and drop to rearrange

#### Apply Discounts

1. **Header Discount**: Enter percentage (0-100) in discount field
2. Discount applies to all line items
3. System recalculates totals automatically

#### Set VAT

1. **DKK Invoices**: VAT defaults to 25%
2. **Other Currencies**: VAT set to 0%
3. Can be manually adjusted if needed

### 6. Preview Pricing

1. Click **"Preview"** button
2. Review calculated amounts:
   - Sum before discounts
   - Discount amount
   - Sum after discounts
   - VAT amount
   - Grand total
3. Check calculation breakdown for transparency

### 7. Save Draft

1. Click **"Save Draft"** to preserve changes
2. Draft remains editable
3. Can return to edit later

### 8. Finalize Invoice

#### Create Standard Invoice

1. Review all details carefully
2. Click **"Create Invoice"** button
3. Confirm in dialog: "Are you sure you want to create this invoice?"
4. System:
   - Assigns invoice number
   - Changes status to CREATED
   - Generates PDF
   - Makes invoice read-only

#### Create Phantom Invoice

For placeholder/planning invoices:
1. Click **"Create Phantom"** button
2. Creates invoice without invoice number
3. Not sent to e-conomics
4. Used for internal tracking

### 9. Post-Creation Actions

#### View PDF
1. Click **"View PDF"** button
2. PDF opens in new window
3. Can download or print

#### Send to Client
1. Status changes to SUBMITTED when sent
2. Currently manual process (email integration pending)

#### Create Credit Note
If corrections needed:
1. Click **"Create Credit Note"**
2. System creates negative invoice
3. References original invoice
4. Edit as needed

## Important Notes

### What You Cannot Do

- **Edit finalized invoices** (status != DRAFT)
- **Delete finalized invoices**
- **Change invoice numbers** (system-assigned)
- **Create duplicate invoices** for same period

### Best Practices

1. **Review Before Finalizing**: Double-check all amounts and details
2. **Use Descriptions**: Add clear descriptions for transparency
3. **Apply Discounts Carefully**: Header discount affects all items
4. **Check VAT Settings**: Especially for non-DKK invoices
5. **Save Drafts Frequently**: Preserve work in progress

## Common Scenarios

### Monthly Recurring Invoice

1. Navigate to current month
2. Find recurring project
3. Click "New draft"
4. Review and adjust if needed
5. Create invoice

### Multi-Project Invoice

1. Create draft from first project
2. Add line items manually for other projects
3. Organize with drag-and-drop
4. Apply appropriate discount
5. Finalize

### Correction Invoice

1. Find original invoice
2. Click "Create Credit Note"
3. Creates negative amounts
4. Create new correct invoice separately

## Troubleshooting

### "Cannot Create Draft" Error

**Possible Causes:**
- No client data on contract
- No registered work for period
- Missing permissions

**Solution:**
- Verify contract has client information
- Check work is registered for period
- Contact admin for permissions

### Missing Line Items

**Cause:** Work not properly registered
**Solution:** Check time registration for period

### Incorrect Amounts

**Cause:** Wrong rates or hours
**Solution:** Edit in DRAFT status before finalizing

### Cannot Edit Invoice

**Cause:** Invoice already finalized
**Solution:** Create credit note for corrections

## Tips for Efficiency

### Keyboard Shortcuts
- **Tab**: Navigate between fields
- **Enter**: Submit forms
- **Escape**: Close dialogs

### Bulk Operations
- Create multiple drafts in session
- Review all before finalizing
- Process in batch

### Templates (Future)
- Save common invoice formats
- Reuse for similar projects
- Maintain consistency

## Next Steps

After creating invoices:
1. [Manage Bonuses](managing-bonuses.md) - Assign bonuses
2. [Track Payments](../future-enhancements.md#payment-management) - Monitor payment status
3. [Generate Reports](../future-enhancements.md#advanced-reporting) - Financial reporting

## Need Help?

- Check [Troubleshooting Guide](../technical/troubleshooting.md)
- Contact Finance Team
- Submit support ticket
