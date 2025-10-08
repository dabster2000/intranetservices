# Repository Structure Analysis

## Technology Stack
- **Build Tool**: Maven (Java 21)
- **Framework**: Quarkus 3.26.1
- **Persistence**: JPA/Hibernate with Panache
- **Architecture**: REST-based microservices with limited reactive support

## Module Overview
Single Maven module: `dk.trustworks.intranet:intranetservices:1.0.0-SNAPSHOT`

## Package Structure

### Main Aggregates/Domains (under `dk.trustworks.intranet.aggregates`)
- **accounting** - Accounting and financial operations
- **availability** - Employee availability tracking
- **bidata** - Business intelligence data
- **budgets** - Budget management
- **client** - Client domain events
- **conference** - Conference management
- **crm** - Customer relationship management
- **invoice** - Invoice generation and bonus calculations
  - **bonus** - Invoice bonus subsystem (eligibility, pools, calculations)
- **lunch** - Lunch ordering system
- **revenue** - Revenue tracking and reporting
- **sender** - Event sending infrastructure
- **utilization** - Resource utilization metrics
- **users** - User aggregate with events, queries, services
- **work** - Work tracking events

### API Gateway (`dk.trustworks.intranet.apigateway`)
Central routing layer with 30+ resources including:
- Contract, Work, Client, Project, Task management
- Employee, Role, Team management
- Knowledge, Culture, Finance, Sales endpoints
- Form data handling

### Services
- **achievementservice** - Achievement tracking
- **communicationsservice** - Email (bulk & individual), Slack integration
- **cultureservice** - Culture & performance management
- **expenseservice** - Expense tracking and validation
- **fileservice** - File and photo storage (S3)
- **financeservice** - Financial data integration (E-conomic)
- **knowledgeservice** - Courses, certifications, conferences, project descriptions
- **marginservice** - Margin calculations
- **newsservice** - Internal news
- **sales** - Sales leads and coffee dates
- **userservice** - User management and authentication

### Infrastructure
- **batch** - Batch job scheduling and monitoring
- **contracts** - Contract validation and management
- **dao** - Data access objects (CRM, work, bubble)
- **domain** - Core domain entities (user, CV)
- **jobs** - Scheduled jobs (birthday notifications, Slack sync, budget updates)
- **messaging** - Event-driven architecture
  - **consumers** - Event consumers (user status, salary, contract, work, budget)
  - **producers** - Event producers (expense created)
  - **outbox** - Outbox pattern for reliable messaging
  - **bridge** - External event bridge
  - **routing** - Event routing registry
- **recalc** - Recalculation services for aggregates
- **bi** - Business intelligence calculators (salary, availability, utilization, budget, work)

## REST Endpoints Summary
- **Total Resources**: 95 JAX-RS resources
- **HTTP Methods**: GET, POST, PUT, DELETE, PATCH
- **Media Types**: Primarily JSON (application/json)
- **Reactive Support**: Minimal (1 SSE endpoint found with Uni/Multi)

## Entity Model
- **Total Entities**: 125 JPA entities
- **Relationships**: 62 occurrences across 40 entities
  - @OneToMany: Collection ownership patterns
  - @ManyToOne: Parent references
  - @ManyToMany: Many-to-many relationships
- **Primary Keys**: Mix of @Id, @GeneratedValue, and composite keys
- **Inheritance**: Some entities extend PanacheEntity

## Service Layer
- **Total Services**: 137 services
- **Scopes**: @ApplicationScoped, @Singleton
- **Transaction Management**: @Transactional annotations
- **Patterns**: Domain services, application services, query services

## Integration Points

### Messaging (Reactive Messaging)
- **Consumers**: 7 consumers (@Incoming)
  - User status updates
  - User salary updates
  - Contract consultant updates
  - Work updates
  - Budget updates
  - Expense created
  - Work handler
- **Producers**: 1 producer (@Outgoing)
  - Expense created

### External REST Clients
- **Total**: 8 @RegisterRestClient interfaces
  - E-conomic API (multiple endpoints)
  - OpenAI Client
  - Mail API
  - Invoice APIs
  - Currency API

### Async/Reactive Patterns
- **Limited adoption**: Only 1 file with Uni/Multi (SSEResource)
- **Primarily synchronous**: Most endpoints return Response/RestResponse

## Batch Processing
- **Framework**: JBatch (Jakarta Batch)
- **Job Tracking**: Custom batch execution tracking
- **Scheduled Jobs**: Quarkus Scheduler
- **Recalculation Pipeline**: Status, salary, contract consultant recalcs

## Key Observations for Migration

### Strengths
1. Clear domain separation with aggregates
2. Event-driven foundation with messaging consumers
3. Outbox pattern implemented for reliable messaging
4. Strong service layer with clear boundaries

### Challenges
1. **Synchronous Dominance**: 94 of 95 resources are synchronous REST
2. **Limited Reactive**: Only 1 reactive endpoint (SSE)
3. **Tight Coupling**: Many resources call multiple services
4. **Large Transactions**: Some @Transactional methods span multiple aggregates

### Migration Readiness
- **High Potential**: Invoice bonus, user aggregates, work tracking
- **Medium Potential**: Budget, utilization, revenue (read-heavy)
- **Low Priority**: Legacy resources, simple CRUD operations

