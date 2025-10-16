# Invoice System - System Overview

## Architecture Overview

The Invoice System follows a layered architecture with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────┐
│                 UI Layer (Vaadin Views)                  │
│  InvoiceView | InvoiceAdminDashboard | BonusApprovalView │
├─────────────────────────────────────────────────────────┤
│             Service Layer (InvoiceService)               │
│  Facade pattern with caching and async processing        │
├─────────────────────────────────────────────────────────┤
│        REST Client Layer (InvoiceRestService)            │
│  Paginated queries, batch operations, pricing preview    │
├─────────────────────────────────────────────────────────┤
│          Backend API (Spring Boot Services)              │
│  Business logic, validation, pricing engine              │
├─────────────────────────────────────────────────────────┤
│              Database Layer (MariaDB)                    │
│  Read-only connections, explicit transactions for writes │
└─────────────────────────────────────────────────────────┘
```

## Core Components

### InvoiceView (UI Layer)
**Location**: `dk.trustworks.intranet.views.invoice.InvoiceView`

Primary user interface for invoice management with:
- Asynchronous data loading with progress indicators
- Multi-layer caching for clients, projects, and sums
- Lazy content loading in accordions
- URL parameter support for deep linking
- Drag-and-drop line item reordering

### InvoiceService (Service Layer)
**Location**: `dk.trustworks.intranet.services.InvoiceService`

Facade service providing:
- Unified interface for all invoice operations
- Method-level caching with Caffeine
- Cache eviction on mutations
- Transaction management
- Business logic orchestration

### InvoiceRestService (REST Client Layer)
**Location**: `dk.trustworks.intranet.network.rest.InvoiceRestService`

REST client handling:
- HTTP communication with API Gateway
- DTO mapping and transformation
- Pagination and filtering
- Error handling and retry logic
- JWT token injection

### Database Layer
- **Read-Only by Default**: Database connections are read-only for safety
- **Explicit Transactions**: Write operations require `@Transactional` annotations
- **Connection Pooling**: HikariCP with 20 max connections
- **Batch Operations**: Support for bulk processing

## Data Flow

### Invoice Creation Flow
```
User Input → InvoiceView → InvoiceService → InvoiceRestService → API Gateway → Database
                ↓               ↓                   ↓
            Validation      Caching          DTO Mapping
```

### Pricing Calculation Flow
```
Draft Invoice → Pricing Preview API → Calculation Engine → Response with Breakdown
                                            ↓
                                    Apply Discounts & VAT
```

### Bonus Assignment Flow
```
Self-Assignment → Eligibility Check → Create Bonus → Line Selection → Approval Workflow
                        ↓
                  Group Validation
```

## Integration Points

### Internal Integrations
- **Work Service**: Fetches registered hours for invoicing
- **Client Service**: Client data and relationships
- **Project Service**: Project details and contracts
- **User Service**: Consultant information
- **Photo Service**: User avatars and images

### External Integrations
- **E-conomics**: Accounting system synchronization (partial implementation)
- **Azure AD**: Authentication and authorization
- **PDF Service**: Invoice PDF generation

## Performance Architecture

### Caching Strategy
- **L1 Cache**: View-level caches (InvoiceView local maps)
- **L2 Cache**: Service-level Caffeine caches (3-hour TTL)
- **L3 Cache**: Database query caching

### Asynchronous Processing
- **ExecutorService**: Background data loading
- **CompletableFuture**: Parallel operations
- **UI.access()**: Thread-safe UI updates
- **Progressive Loading**: Critical data first

### Lazy Loading Patterns
- **Grid Data Providers**: Server-side pagination
- **Accordion Content**: Load on expand
- **Tab Content**: Load on selection

## Security Architecture

### Authentication
- Azure AD/Entra ID OAuth2
- JWT token-based API access
- Session management with remember-me

### Authorization
- Role-based access control (RBAC)
- View-level security with `@RolesAllowed`
- Method-level security with `@PreAuthorize`
- Data-level security through service layer

### Audit Trail
- All modifications tracked with user attribution
- Timestamps for create/update operations
- Bonus approval audit logging
- Invoice status change tracking

## Key Design Patterns

### Facade Pattern
InvoiceService provides a simplified interface to complex subsystems

### Repository Pattern
Data access abstracted through repository interfaces

### Data Transfer Object (DTO)
Clean separation between API and domain models

### Observer Pattern
Grid selection listeners for reactive UI updates

### Builder Pattern
Fluent interfaces for complex object construction

## Scalability Considerations

### Horizontal Scaling
- Stateless services enable clustering
- Cache coordination via distributed caching (future)
- Load balancing at API Gateway level

### Vertical Scaling
- Configurable connection pools
- Adjustable cache sizes
- Tunable async thread pools

### Database Optimization
- Strategic indexes for common queries
- Read-only replicas for reporting (future)
- Materialized views for aggregations (planned)

## Monitoring and Observability

### Performance Monitoring
- Method execution timing
- Cache hit rate tracking
- Connection pool metrics
- Query performance logging

### Application Monitoring
- Health checks at `/actuator/health`
- Metrics at `/actuator/metrics`
- Custom business metrics
- Error rate tracking

### Logging Strategy
- Structured logging with correlation IDs
- Different log levels per package
- Audit logs for compliance
- Performance logs for optimization

## Deployment Architecture

### Current State
- Single deployment unit
- Embedded Tomcat server
- Direct database connections
- File-based PDF storage

### Future State (Planned)
- Microservices decomposition
- Containerized deployments
- API Gateway routing
- Object storage for PDFs
