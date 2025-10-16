# Invoice System Documentation

**Version:** 4.0  
**Last Updated:** 2025-10-11  
**Status:** Production with Modern Bonus System

## Overview

The Trustworks Invoice System is a comprehensive billing and financial management module handling the complete invoice lifecycle from creation to payment. This documentation covers all aspects of the invoice system including types, pricing, bonuses, integrations, and operational workflows.

## Documentation Structure

### 📁 Architecture
- [System Overview](architecture/system-overview.md) - Complete system architecture and components
- [Data Model](architecture/data-model.md) - Entity relationships and database schema
- [Invoice Types](architecture/invoice-types.md) - All 5 invoice types detailed
- [Integration Architecture](architecture/integration-architecture.md) - e-conomic and internal system integrations

### 📁 Implementation
- [Invoice Lifecycle](implementation/invoice-lifecycle.md) - DRAFT → PAID complete workflow
- [Pricing Engine](implementation/pricing-engine.md) - Server-side calculation and preview API
- [Bonus System](implementation/bonus-system.md) - Modern self-assign bonus with approval workflow
- [Credit Notes](implementation/credit-notes.md) - Credit note relationships and workflows
- [Validation Rules](implementation/validation-rules.md) - All business rules and constraints

### 📁 API Reference
- [Invoice REST API](api/invoice-rest-api.md) - Core invoice endpoints
- [Pricing API](api/pricing-api.md) - /pricing/preview calculation API
- [Bonus API](api/bonus-api.md) - Bonus management and eligibility APIs

### 📁 User Guides
- [Creating Invoices](user-guides/creating-invoices.md) - Step-by-step invoice creation
- [Managing Bonuses](user-guides/managing-bonuses.md) - Self-assign and approval workflows
- [Credit Note Workflow](user-guides/credit-note-workflow.md) - Creating and managing credit notes
- [Internal Invoices](user-guides/internal-invoices.md) - Inter-company invoicing

### 📁 Technical
- [Performance Optimization](technical/performance-optimization.md) - Caching, async, lazy loading strategies
- [Security Model](technical/security-model.md) - RBAC and permission matrix
- [Troubleshooting](technical/troubleshooting.md) - Common issues and solutions

### 📁 Future
- [Future Enhancements](future-enhancements.md) - Unimplemented features from specification

## Quick Start

### For Developers
1. Review the [System Overview](architecture/system-overview.md) to understand the architecture
2. Check the [Data Model](architecture/data-model.md) for entity relationships
3. Reference the [Invoice REST API](api/invoice-rest-api.md) for endpoint documentation

### For Users
1. Start with [Creating Invoices](user-guides/creating-invoices.md) for basic operations
2. Learn about [Managing Bonuses](user-guides/managing-bonuses.md) for bonus workflows
3. Understand [Credit Note Workflow](user-guides/credit-note-workflow.md) for corrections

### For Finance Teams
1. Review [Invoice Types](architecture/invoice-types.md) to understand all invoice categories
2. Study the [Pricing Engine](implementation/pricing-engine.md) for calculation details
3. Check [Bonus System](implementation/bonus-system.md) for compensation workflows

## Core Features

### ✅ Implemented
- **5 Invoice Types**: Regular, Credit Note, Phantom, Internal, Internal Service
- **Draft Management**: Full CRUD operations with draft state
- **Pricing Engine**: Server-side calculation with transparent breakdown
- **Modern Bonus System**: Self-assignment, approval workflow, line-level allocation
- **Credit Notes**: Full relationship tracking and visualization
- **Performance Optimization**: Multi-layer caching, async loading, lazy rendering
- **Security**: Role-based access control with audit trails
- **UI Features**: Month navigation, drag-drop reordering, progressive loading

### ⚠️ Partial Implementation
- **E-conomics Integration**: Fields exist, sync process needs documentation
- **Reporting**: Basic dashboards exist, advanced analytics in development

### ❌ Not Implemented
- **Payment Recording UI**: Status exists but no UI for marking as paid
- **Bulk Operations**: Mass invoice creation/approval not available
- **Advanced Discounts**: SKI discount tracking exists, rules not documented
- **Email Notifications**: No automated email system

## Key Concepts

### Invoice Status Flow
```
DRAFT → CREATED → SUBMITTED → PAID
         ↓
    CREDIT_NOTE
```

### Bonus Approval Flow
```
PENDING → APPROVED
   ↓
REJECTED
```

### Fiscal Year
- Runs July 1 - June 30
- Display format: "FY 2024/25"
- Used for bonus eligibility groups

## Technology Stack

- **Backend**: Java 21, Spring Boot 3.5.3
- **Frontend**: Vaadin 24.9 Enterprise
- **Database**: MariaDB with JPA/Hibernate
- **Caching**: Caffeine with 3-hour default TTL
- **Authentication**: Azure AD/Entra ID OAuth2
- **PDF Generation**: Server-side templates

## Performance Metrics

| Metric | Target | Actual |
|--------|--------|--------|
| Dashboard Load | < 2s | 1.8s |
| Invoice Grid (1000 rows) | < 1s | 0.7s |
| Single Invoice Fetch | < 100ms | 85ms |
| Bonus Calculation | < 200ms | 150ms |
| Cache Hit Rate | > 80% | 85% |

## Related Documentation

- [Immutable Snapshot System](../immutable-snapshot-system.md) - For bonus lock-in functionality
- [Partner Bonus Architecture](../bonus/PARTNER_BONUS_SNAPSHOT_GUIDE.md) - Partner-specific bonus features
- [Caching Design](../wiring/caching-design.md) - System-wide caching strategy

## Support

For questions or issues:
1. Check the [Troubleshooting Guide](technical/troubleshooting.md)
2. Review application logs
3. Contact the development team

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 4.0 | 2025-10-11 | Complete documentation restructure |
| 3.0 | 2025-09-27 | Modern bonus system implementation |
| 2.0 | 2024-12 | Eligibility groups added |
| 1.0 | 2024-10 | Initial implementation |
