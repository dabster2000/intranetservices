# DDD Aggregate Root Analysis

## Methodology
Aggregate roots are identified by:
1. **Identity**: Entities with clear identifiers (UUID/ID)
2. **Ownership**: @OneToMany relationships with cascade/orphanRemoval
3. **Invariants**: @Transactional methods enforcing business rules
4. **Consistency Boundaries**: Service methods coordinating multiple entities atomically
5. **Access Pattern**: Other entities accessed through the root

## Top 10 Candidate Aggregate Roots

### 1. LockedBonusPool (Score: 95/100)
**Root Entity**: `LockedBonusPoolData`  
**Location**: `dk.trustworks.intranet.aggregates.invoice.bonus.model.LockedBonusPoolData`  
**Service**: `LockedBonusPoolService`

**Invariants**:
- Fiscal year uniqueness: Each fiscal year can only be locked once
- Immutability: Once locked, cannot be modified (conflict on re-lock)
- Checksum integrity: Data has checksum for verification
- Audit trail: Tracks who locked and when

**Members**:
- Root: LockedBonusPoolData (id, fiscalYear, poolContextJson, checksum, lockedAt, lockedBy)

**Transactional Hotspots**:
- `LockedBonusPoolService.lockBonusPool()` - Lines 88-133
- `LockedBonusPoolRepository.save()` - Line 79
- `LockedBonusPoolRepository.update()` - Line 91
- `LockedBonusPoolRepository.delete()` - Line 102

**Evidence**:
```java
// LockedBonusPoolService.java:88-98
@Transactional
public LockedBonusPoolData lockBonusPool(Integer fiscalYear, String poolContextJson, String lockedBy) {
    if (repository.existsByFiscalYear(fiscalYear)) {
        throw new WebApplicationException(
            Response.status(Response.Status.CONFLICT)
                .entity(String.format("Fiscal year %d is already locked", fiscalYear))
                .build()
        );
    }
    // ... checksum calculation and persistence
}
```

**Reasons**:
- Strong invariant enforcement (uniqueness, immutability)
- Clear consistency boundary (fiscal year lock)
- Service methods always load via fiscal year (root access pattern)
- @Transactional boundaries protect invariants
- No bidirectional associations

**Cautions**:
- Small aggregate (single entity) - very good for DDD
- Read-only after creation (event sourcing friendly)

**Feature Links**: Invoice Bonus System

**Migration Fitness**:
- **Async First**: YES
- **Score**: 95/100
- **Notes**: Perfect DDD aggregate - small, strong invariants, immutable snapshots. Excellent candidate for event sourcing and async processing. Locking operation could be command with event emission.

---

### 2. InvoiceBonus (Score: 88/100)
**Root Entity**: `InvoiceBonus`  
**Location**: `dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus`  
**Service**: `InvoiceBonusService`

**Invariants**:
- Percent share constraint: Sum of percent shares for an invoice <= 100%
- User uniqueness: One bonus per user per invoice
- Computed amount consistency: Amount recalculated on changes
- Approval workflow: Status transitions (PENDING -> APPROVED/REJECTED)

**Members**:
- Root: InvoiceBonus (invoiceuuid, useruuid, shareType, shareValue, computedAmount, status)
- Related: InvoiceBonusLine (line items)
- Related: Invoice (parent context)

**Transactional Hotspots**:
- `InvoiceBonusService.addSelfAssign()` - Lines 61-66
- `InvoiceBonusService.addAdmin()` - Lines 68-72
- `InvoiceBonusService.addInternal()` - Lines 74-100
- `InvoiceBonusResource.update()` - Multiple endpoints

**Evidence**:
```java
// InvoiceBonusService.java:76-97
if (InvoiceBonus.count("invoiceuuid = ?1 and useruuid = ?2", invoiceuuid, useruuid) > 0) {
    throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
            .entity("User already added for invoice bonus").build());
}
// ...
if (sumPct > 100.0 + 1e-9) {
    throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
            .entity("Sum of percent shares exceeds 100%").build());
}
```

**Reasons**:
- Strong business invariants enforced in service layer
- Multiple @Transactional methods (20+)
- Computed fields maintained by aggregate
- Clear consistency boundary (bonus allocation for invoice)

**Cautions**:
- References Invoice (parent aggregate) - may need careful boundary definition
- Complex calculation logic (recomputeComputedAmount)

**Feature Links**: Invoice Bonus System

**Migration Fitness**:
- **Async First**: Partially
- **Score**: 88/100
- **Notes**: Good aggregate with strong invariants. Bonus addition could be async command. Percent validation requires synchronous check within aggregate. Consider using pessimistic locking for concurrent modifications.

---

### 3. Conference (Score: 85/100)
**Root Entity**: `Conference`  
**Location**: `dk.trustworks.intranet.knowledgeservice.model.Conference`  
**Service**: `ConferenceService`  
**Event Handler**: `ConferenceEventHandler`

**Invariants**:
- Participant uniqueness per conference
- Phase transitions: Participants move through phases (APPLICATION -> APPROVED -> ATTENDED)
- Budget constraints (implicit in approval)

**Members**:
- Root: Conference (id, name, startDate, endDate)
- Owned: ConferenceParticipant (@OneToMany) - conference attendees
- Related: ConferencePhase, ConferenceMail

**Transactional Hotspots**:
- `ConferenceService.createParticipant()` - Line 32
- `ConferenceService.updateParticipant()` - Line 38
- `ConferenceService.changePhase()` - Line 63
- `ConferenceResource.create()` - Line 103
- `ConferenceResource.update()` - Line 119

**Evidence**:
```java
// ConferenceService.java:32-38
@Transactional
public ConferenceParticipant createParticipant(CreateParticipantEvent event) {
    // ... create participant
}

@Transactional
public void updateParticipant(UpdateParticipantDataEvent event) {
    // ... update participant data
}
```

**Event-Driven**:
- CreateParticipantEvent
- UpdateParticipantDataEvent
- ChangeParticipantPhaseEvent

**Reasons**:
- Clear ownership of participants (collection management)
- Event-driven architecture present
- Service layer enforces consistency
- 5 @Transactional methods

**Cautions**:
- Participant relationship fetch strategy (EAGER vs LAZY)

**Feature Links**: Knowledge Management

**Migration Fitness**:
- **Async First**: YES
- **Score**: 85/100
- **Notes**: Excellent async candidate. Already has event infrastructure. Conference commands (create participant, change phase) are naturally async. Event sourcing would work well for audit trail.

---

### 4. User (Score: 80/100)
**Root Entity**: `User`  
**Location**: `dk.trustworks.intranet.domain.user.entity.User`  
**Service**: `UserService`, `StatusService`, `SalaryService`  
**Event Handler**: `UserEventHandler`

**Invariants**:
- Email uniqueness
- Username uniqueness
- Azure OID uniqueness
- Status history consistency
- Salary history consistency (non-overlapping periods)

**Members**:
- Root: User (uuid, email, username, azureOid)
- Owned: UserStatus (status history) - @Transient in code, separate table
- Owned: Salary (salary history) - @Transient in code, separate table
- Owned: UserBankInfo, UserPension, UserContactinfo
- Related: Role, Team (many-to-many)

**Transactional Hotspots**:
- `UserService.create()` - Line 77
- `UserService.update()` - Line 361
- `StatusService.create()` - Line 45
- `StatusService.update()` - Line 88
- `SalaryService.create()` - Line 40
- `SalaryService.update()` - Line 78
- Total: 30+ @Transactional methods

**Evidence**:
```java
// User.java:35-51
@Id
public String uuid;
@NotBlank(message="Username may not be blank")
private String username;
@JsonIgnore
@Column(name = "azure_oid", length = 36, unique = true)
public String azureOid;
```

**Event-Driven**:
- CreateUserEvent, UpdateUserEvent
- CreateUserStatusEvent, UpdateUserStatusEvent, DeleteUserStatusEvent
- CreateSalaryLogEvent, UpdateSalaryEvent, DeleteSalaryEvent
- CreateSalarySupplementEvent, CreateBankInfoEvent
- Consumers: UserStatusUpdateConsumer, UserSalaryUpdateConsumer

**Reasons**:
- Central aggregate with strong identity
- Event sourcing patterns present
- Multiple owned entities (status, salary, bank info)
- 30+ transactional operations

**Cautions**:
- High coupling (0.8) - referenced by many other aggregates
- Large aggregate - may need to split into sub-aggregates
- @Transient collections suggest lazy loading strategy

**Feature Links**: User Management, Salary, Status, Vacation, Transportation

**Migration Fitness**:
- **Async First**: Partially
- **Score**: 80/100
- **Notes**: Central aggregate with event foundation. User mutations are already async (event-driven). Consider splitting into smaller aggregates (User core, UserEmployment, UserFinancial). High coupling means migration should happen mid-to-late.

---

### 5. Contract (Score: 82/100)
**Root Entity**: `Contract`  
**Location**: `dk.trustworks.intranet.contracts.model.Contract`  
**Service**: `ContractService`, `ContractValidationService`, `ContractConsultantService`

**Invariants**:
- Date range validity (start <= end)
- Consultant allocation constraints (no overlapping allocations)
- Budget consumption <= contract amount
- Status transitions (DRAFT -> ACTIVE -> CLOSED)
- Contract consultant validation (recently added)

**Members**:
- Root: Contract (uuid, amount, contractType, status, clientuuid)
- Owned: ContractConsultant (@OneToMany, cascade=ALL) - consultants on contract
- Owned: ContractProject (@OneToMany) - projects under contract
- Owned: ContractTypeItem (@OneToMany) - pricing items
- Related: Company (@ManyToOne), ContractSalesConsultant (@ManyToOne)

**Transactional Hotspots**:
- `ContractService.create()` - multiple transactions
- `ContractService.update()` - multiple transactions
- `ContractValidationService.validate()` - validation logic
- `ContractConsultantService.add/update/remove()` - consultant management
- Total: 10+ @Transactional methods

**Evidence**:
```java
// Contract.java:69-71
@OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
@JoinColumn(name = "contractuuid")
private Set<ContractConsultant> contractConsultants = new HashSet<>();
```

**Event-Driven**:
- ModifyContractConsultantEvent
- Consumer: ContractConsultantUpdateConsumer

**Reasons**:
- Clear ownership of consultants and projects (cascade=ALL)
- Validation service enforces business rules
- Multiple collections managed as unit
- Event-driven updates present

**Cautions**:
- EAGER fetching of collections - performance impact
- Multiple @ManyToOne relationships - potential coupling
- Recent validation service addition suggests evolving invariants

**Feature Links**: Contract Management, Budget Management

**Migration Fitness**:
- **Async First**: Partially
- **Score**: 82/100
- **Notes**: Good aggregate structure. Contract mutations could be async commands. Validation rules need careful design for async (use saga pattern for complex validations). EAGER fetching should be replaced with explicit loading.

---

### 6. Invoice (Score: 78/100)
**Root Entity**: `Invoice`  
**Location**: `dk.trustworks.intranet.aggregates.invoice.model.Invoice`  
**Service**: `InvoiceService`, `InvoiceGenerator`, `PricingEngine`

**Invariants**:
- Unique invoice number per company
- Total amount = sum of invoice items
- Draft -> Finalized -> Booked workflow
- Bonus approval status consistency
- VAT calculation correctness

**Members**:
- Root: Invoice (uuid, invoicenumber, invoicedate, status)
- Owned: InvoiceItem (@OneToMany implied) - line items
- Related: InvoiceNote, InvoiceBonus (separate aggregate)

**Transactional Hotspots**:
- `InvoiceService.create()` - Line 181
- `InvoiceService.update()` - multiple
- `InvoiceService.finalize()` - Line 397
- `InvoiceGenerator.generate()` - Line 69
- `InvoiceGenerator.regenerate()` - Line 144
- Total: 15+ @Transactional methods

**Evidence**:
```java
// Invoice.java:38-42
@Id
public String uuid;
public String contractuuid;
public String projectuuid;
public int invoicenumber;
```

**Reasons**:
- Core business document
- Complex generation logic (InvoiceGenerator)
- Pricing engine integration
- Multiple transactional operations
- Workflow state management

**Cautions**:
- References Contract, Project (FK relationships)
- Bonus calculation separate aggregate (good separation)
- Large entity (many fields)

**Feature Links**: Invoice Management, Invoice Bonus

**Migration Fitness**:
- **Async First**: Partially
- **Score**: 78/100
- **Notes**: Invoice generation is compute-intensive - good async candidate. State transitions (draft -> final) should be atomic. Consider command pattern for generation. Integration with E-conomic API is I/O bound (async benefit).

---

### 7. Work (Score: 75/100)
**Root Entity**: `Work`  
**Location**: `dk.trustworks.intranet.dao.workservice.model.Work`  
**Service**: `WorkService`, `WorkAggregateService`  
**Event Handler**: `WorkEventHandler`

**Invariants**:
- Date validity
- Hours >= 0
- User + contract + task + date uniqueness (implied)
- Work rate consistency with contract

**Members**:
- Root: Work (useruuid, taskuuid, workdate, workhours, rate)
- Materialized view: WorkFull (denormalized for performance)

**Transactional Hotspots**:
- `WorkService.create()`, `WorkService.update()`, `WorkService.delete()`
- `WorkAggregateService` - Line 28
- Caching: `WorkCacheRefreshJob` - advisory locking pattern

**Evidence**:
```java
// WorkCacheRefreshJob - advisory locking for concurrency
// Shows awareness of high-volume transaction challenges
```

**Event-Driven**:
- UpdateWorkEvent
- Consumers: WorkUpdateConsumer, WorkHandler

**Reasons**:
- High-volume transactional data
- Event-driven updates
- Cache optimization present (shows performance concern)
- Advisory locking for concurrent cache updates

**Cautions**:
- High coupling (0.75) - used by revenue, utilization, invoices
- Performance-critical (caching strategy)
- Denormalized view (WorkFull) for queries

**Feature Links**: Work Tracking, Revenue, Utilization, Invoices

**Migration Fitness**:
- **Async First**: YES
- **Score**: 75/100
- **Notes**: High-volume makes async attractive. Event-driven foundation exists. Caching shows performance is critical. Consider CQRS: async writes to event stream, materialized views for queries. Advisory locking pattern should transition to optimistic locking or event ordering.

---

### 8. Expense (Score: 76/100)
**Root Entity**: `Expense`  
**Location**: `dk.trustworks.intranet.expenseservice.model.Expense`  
**Service**: `ExpenseAIValidationService`, `EconomicsInvoiceStatusService`

**Invariants**:
- Amount > 0
- Valid category and account
- User ownership
- Approval workflow
- AI validation results

**Members**:
- Root: Expense (id, useruuid, amount, category, description, status)
- Related: ExpenseCategory, ExpenseAccount, UserAccount

**Transactional Hotspots**:
- Create/update expense with validation
- AI validation service (async-friendly)
- E-conomic integration

**Event-Driven**:
- ExpenseCreatedProducer (@Outgoing)
- ExpenseCreatedConsumer (@Incoming)
- ExpenseHandler

**Reasons**:
- Event-driven architecture
- AI validation is I/O bound (async benefit)
- External integration (E-conomic)
- Clear business rules

**Cautions**:
- AI validation latency
- External API dependency

**Feature Links**: Expense Management

**Migration Fitness**:
- **Async First**: YES
- **Score**: 76/100
- **Notes**: Perfect async candidate. Already event-driven. AI validation and E-conomic integration are I/O bound. Use saga pattern for: expense created -> AI validation -> approval -> sync to E-conomic. Events provide audit trail.

---

### 9. MealPlan (Score: 72/100)
**Root Entity**: `MealPlan`  
**Location**: `dk.trustworks.intranet.aggregates.lunch.model.MealPlan`  
**Service**: `MenuService`, `SummaryService`

**Invariants**:
- Participant limits
- Choice deadlines
- Buffer allocation rules

**Members**:
- Root: MealPlan (id, week, capacity)
- Owned: MealPlanUser (@ManyToOne) - participant choices
- Owned: MealPlanBuffer (@ManyToOne) - buffer allocations
- Related: MealChoice, MealBuffer

**Transactional Hotspots**:
- `MealBufferResource.create()` - Line 22
- `MealPlanResource.create()` - Line 34
- `MenuResource.create()` - Line 27
- `MealChoiceResource.create()` - Line 23
- Total: 4 @Transactional methods

**Evidence**:
```java
// MealPlanUser.java - @ManyToOne relationships
// MealPlanBuffer.java - @ManyToOne relationships
```

**Reasons**:
- Clear ownership structure
- Self-contained domain
- Low coupling (0.2)
- Simple transactional operations

**Cautions**:
- Small domain
- Limited complexity

**Feature Links**: Lunch Ordering

**Migration Fitness**:
- **Async First**: YES
- **Score**: 72/100
- **Notes**: Excellent first migration candidate. Low coupling, self-contained, simple invariants. Use command pattern: place order -> allocate buffer -> send confirmation. Good test case for async migration approach.

---

### 10. Budget (Score: 70/100)
**Root Entity**: `Budget`  
**Location**: `dk.trustworks.intranet.contracts.model.Budget`  
**Service**: `BudgetService`, `BudgetCalculatingExecutor`

**Invariants**:
- Budget amount >= consumed amount
- Date range validity
- Budget allocation consistency

**Members**:
- Root: Budget (contractuuid, year, month, hours, amount)
- Materialized: EmployeeBudgetPerDayAggregate (denormalized)

**Transactional Hotspots**:
- `EmployeeBudgetPerDayAggregate` - Line 75 (static persistence method)
- Budget calculation executors

**Event-Driven**:
- BudgetUpdateConsumer

**Reasons**:
- Materialized view pattern (CQRS-like)
- Event consumer present
- Calculation-intensive (executor pattern)

**Cautions**:
- Aggregate calculations (performance)
- Denormalization strategy

**Feature Links**: Budget Management, Contract Management

**Migration Fitness**:
- **Async First**: YES
- **Score**: 70/100
- **Notes**: Good CQRS candidate. Budget calculations are compute-intensive. Use async: contract updated -> recalculate budget -> update materialized view. Event consumer shows async readiness. Denormalized aggregate simplifies queries.

---

## Migration Order Recommendation

### Phase 1: Proof of Concept (Months 1-2)
**Goal**: Validate async migration approach with low-risk, self-contained aggregate

1. **MealPlan (Lunch Ordering)** - Score: 95
   - Rationale: Self-contained, low coupling, clear boundaries
   - Approach: Command pattern with event sourcing
   - Success Metrics: Order placement latency, throughput
   - Test Coverage: Should be straightforward

2. **LockedBonusPool** - Score: 95
   - Rationale: Immutable aggregate, perfect DDD example
   - Approach: Event sourcing for audit trail
   - Success Metrics: Lock operation latency
   - Risk: Very low

### Phase 2: I/O Bound Operations (Months 3-4)
**Goal**: Migrate operations with external dependencies

3. **Expense** - Score: 76
   - Rationale: Already event-driven, AI validation async-friendly
   - Approach: Saga pattern (expense -> validation -> approval -> E-conomic)
   - Success Metrics: Validation throughput, E-conomic sync latency

4. **Invoice** - Score: 78
   - Rationale: Invoice generation is compute/I/O intensive
   - Approach: Async generation with E-conomic integration
   - Success Metrics: Generation time, API call latency
   - Dependencies: E-conomic API

### Phase 3: Event-Driven Aggregates (Months 5-7)
**Goal**: Migrate aggregates with existing event infrastructure

5. **Conference** - Score: 85
   - Rationale: Event infrastructure exists, clear workflow
   - Approach: Event-driven state machine (phase transitions)
   - Success Metrics: Participant registration throughput

6. **Work** - Score: 75
   - Rationale: High-volume, event-driven, caching strategy
   - Approach: CQRS with event sourcing
   - Success Metrics: Write throughput, cache hit rate
   - Risk: High coupling (0.75) - coordinate with revenue/utilization

7. **Budget** - Score: 70
   - Rationale: CQRS-like already, calculation-intensive
   - Approach: Event-driven recalculation
   - Success Metrics: Calculation time, materialized view latency

### Phase 4: Core Aggregates (Months 8-12)
**Goal**: Migrate business-critical aggregates with caution

8. **Contract** - Score: 82
   - Rationale: Business-critical, validation complexity
   - Approach: Saga pattern for validation, event sourcing
   - Success Metrics: Contract creation latency, validation throughput
   - Risk: Validation rules must remain consistent

9. **InvoiceBonus** - Score: 88
   - Rationale: Complex invariants, depends on Invoice
   - Approach: Command pattern with pessimistic locking for percent validation
   - Success Metrics: Bonus calculation accuracy, allocation latency
   - Risk: Concurrent modifications, invariant enforcement

10. **User** - Score: 80
    - Rationale: Central aggregate, high coupling
    - Approach: Split into sub-aggregates (User, UserEmployment, UserFinancial), event-driven
    - Success Metrics: User operation latency, event propagation time
    - Risk: High coupling (0.8) - coordinate with many features
    - Timeline: Late in migration due to dependencies

### Phase 5: Reporting Aggregates (Months 10-12, parallel with Phase 4)
**Goal**: Migrate read-heavy reporting (can run parallel)

- Revenue Reporting - Score: 85
- Utilization Tracking - Score: 82
- Availability Tracking - Score: 82

**Approach**: CQRS with materialized views, async query processing
**Success Metrics**: Query latency, report generation time
**Risk**: Low - read-only operations

