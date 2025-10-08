# Feature Domain Analysis

## Feature Extraction Methodology
Features are identified by:
1. Primary grouping: First path segment (e.g., `/invoices/*` -> `invoices`)
2. Shared services and entities accessed by endpoints
3. Transactional boundaries (methods that coordinate multiple entities)

## Feature Inventory

### 1. Invoice Management
**Path Pattern**: `/invoices/*`  
**Primary Resource**: `InvoiceResource`  
**Entities**: Invoice, InvoiceItem, InvoiceNote  
**Services**: InvoiceService, InvoiceGenerator, InvoiceNotesService, PricingEngine  
**Endpoints**: 28 REST endpoints
- GET /invoices (list, search, count)
- GET /invoices/{id}, /invoices/months/{month}
- POST /invoices/drafts, /invoices, /invoices/phantoms, /invoices/creditnotes
- PUT /invoices/{id}, /invoices/{id}/bonusstatus
- DELETE /invoices/drafts/{id}
**External Integrations**: E-conomic API, Currency API  
**Messaging**: None directly  
**Coupling Score**: 0.6 (moderate - touches contracts, work, clients)  
**Notes**: Core business capability with complex pricing logic

### 2. Invoice Bonus System
**Path Pattern**: `/invoices/bonus/*`, `/bonuses/*`  
**Primary Resources**: InvoiceBonusResource, BonusEligibilityResource, BonusEligibilityGroupResource, LockedBonusPoolResource  
**Entities**: InvoiceBonus, InvoiceBonusLine, BonusEligibility, BonusEligibilityGroup, LockedBonusPoolData  
**Services**: InvoiceBonusService, LockedBonusPoolService  
**Endpoints**: 15+ REST endpoints
- GET /invoices/bonus-approval, /invoices/my-bonus
- POST /bonuses/eligibility, /bonuses/pools
- PUT /bonuses/eligibility/{id}, /bonuses/pools/{id}/lock
**Transactions**: 20+ @Transactional methods  
**Coupling Score**: 0.7 (moderate-high - depends on invoices, users, work)  
**Notes**: Complex domain with invariants (bonus pools, eligibility rules, locking)

### 3. User Management
**Path Pattern**: `/users/*`  
**Primary Resources**: UserResource, StatusResource, SalaryResource, CompanyUserResource  
**Entities**: User, UserStatus, Salary, UserBankInfo, UserPension, UserContactinfo, Role  
**Services**: UserService, StatusService, SalaryService, UserBankInfoService, UserPensionService, UserContactInfoService  
**Endpoints**: 40+ REST endpoints
- GET /users, /users/{id}, /users/{id}/status, /users/{id}/salary
- POST /users, /users/{id}/status, /users/{id}/salary
- PUT /users/{id}, DELETE /users/{id}
**Messaging**: 
- Consumers: UserStatusUpdateConsumer, UserSalaryUpdateConsumer
- Events: CreateUserEvent, UpdateUserEvent, CreateUserStatusEvent, UpdateUserStatusEvent
**Transactions**: 30+ @Transactional methods  
**Coupling Score**: 0.8 (high - central to many features)  
**Notes**: Core aggregate with event sourcing, high reuse

### 4. Contract Management
**Path Pattern**: `/contracts/*`  
**Primary Resource**: ContractResource  
**Entities**: Contract, ContractConsultant, ContractProject, ContractTypeItem, ContractSalesConsultant  
**Services**: ContractService, ContractValidationService, ContractConsultantService, BudgetService  
**Endpoints**: 20+ REST endpoints
- GET /contracts, /contracts/{id}, /contracts/search
- POST /contracts, /contracts/{id}/consultants
- PUT /contracts/{id}
- DELETE /contracts/{id}
**Messaging**: 
- Consumers: ContractConsultantUpdateConsumer
- Events: ModifyContractConsultantEvent
**Transactions**: 10+ @Transactional methods  
**Coupling Score**: 0.65 (moderate - depends on clients, users, projects)  
**Notes**: Business-critical with validation rules

### 5. Work Tracking
**Path Pattern**: `/work/*`  
**Primary Resource**: WorkResource  
**Entities**: Work, WorkFull, Week  
**Services**: WorkService, WorkAggregateService, WorkCacheRefreshJob  
**Endpoints**: 15+ REST endpoints
- GET /work, /work/users/{id}, /work/contracts/{id}
- POST /work
- PUT /work/{id}
- DELETE /work/{id}
**Messaging**: 
- Consumers: WorkUpdateConsumer, WorkHandler
- Events: UpdateWorkEvent
**Transactions**: 1 @Transactional method  
**Coupling Score**: 0.75 (high - used by revenue, utilization, invoices)  
**Notes**: High-volume transactional data, cache optimization present

### 6. Revenue Reporting
**Path Pattern**: `/company/{id}/revenue/*`  
**Primary Resources**: RevenueResource, EmployeeRevenueResource  
**Services**: RevenueService  
**Endpoints**: 12+ REST endpoints
- GET /revenue/registered, /revenue/invoiced, /revenue/profits
- GET /revenue/registered/months/{month}
- GET /revenue/profits/teams, /revenue/profits/consultants/{id}
**Coupling Score**: 0.4 (low - read-only reporting)  
**Notes**: Read-heavy, good async candidate

### 7. Utilization Tracking
**Path Pattern**: `/company/{id}/utilization/*`  
**Primary Resources**: UtilizationResource, UserUtilizationResource  
**Entities**: EmployeeAggregateData, CompanyAggregateData  
**Services**: UtilizationService, UtilizationCalculatingExecutor  
**Endpoints**: 10+ REST endpoints
- GET /utilization/budget, /utilization/actual, /utilization/gross
- GET /utilization/budget/teams/{id}, /utilization/budget/employees
**Coupling Score**: 0.4 (low - read-only reporting)  
**Notes**: Read-heavy with complex calculations, good async candidate

### 8. Budget Management
**Path Pattern**: `/companies/{id}/budgets/*`, `/users/{id}/budgets/*`  
**Primary Resources**: CompanyBudgetResource, UserBudgetResource  
**Entities**: EmployeeBudgetPerDayAggregate, Budget  
**Services**: BudgetService, BudgetCalculatingExecutor  
**Endpoints**: 10 REST endpoints
- GET /budgets/amount, /budgets/amount/months/{month}
- GET /users/{id}/budgets
**Messaging**: 
- Consumers: BudgetUpdateConsumer
**Transactions**: 1 @Transactional method  
**Coupling Score**: 0.5 (moderate - depends on contracts, work, users)  
**Notes**: Aggregate calculation intensive

### 9. Availability Tracking
**Path Pattern**: `/companies/{id}/availability/*`, `/users/{id}/availability/*`  
**Primary Resources**: CompanyAvailabilityResource, UserAvailabilityResource  
**Entities**: EmployeeAvailabilityPerDayAggregate  
**Services**: AvailabilityService, AvailabilityServiceCache, AvailabilityCalculatingExecutor  
**Endpoints**: 8 REST endpoints
- GET /availability, /availability/months/{month}
- GET /users/{id}/availability
**Coupling Score**: 0.4 (low - read-heavy)  
**Notes**: Read-heavy with caching

### 10. Client/CRM Management
**Path Pattern**: `/clients/*`, `/company/{id}/crm/*`  
**Primary Resources**: ClientResource, ClientDataResource, CrmResource  
**Entities**: Client, Clientdata  
**Services**: ClientService, ClientDataService, CrmService  
**Endpoints**: 15+ REST endpoints
- GET /clients, /clients/{id}
- POST /clients, PUT /clients/{id}
**Coupling Score**: 0.6 (moderate - referenced by contracts, projects)  
**Notes**: Core master data

### 11. Project & Task Management
**Path Pattern**: `/projects/*`, `/tasks/*`  
**Primary Resources**: ProjectResource, TaskResource, ProjectDescriptionResource  
**Entities**: Project, Task, ProjectDescription  
**Services**: ProjectService, TaskService, ProjectDescriptionService  
**Endpoints**: 20+ REST endpoints
- GET /projects, /projects/{id}, /tasks, /tasks/{id}
- POST /projects, /tasks
- PUT /projects/{id}, /tasks/{id}
**Coupling Score**: 0.65 (moderate - linked to contracts, clients, users)

### 12. Expense Management
**Path Pattern**: `/expenses/*`  
**Primary Resources**: ExpenseResource, UserAccountResource  
**Entities**: Expense, UserAccount, ExpenseCategory, ExpenseAccount  
**Services**: ExpenseAIValidationService, EconomicsInvoiceStatusService  
**Endpoints**: 10+ REST endpoints
- GET /expenses, /expenses/{id}
- POST /expenses
**Messaging**: 
- Producers: ExpenseCreatedProducer
- Consumers: ExpenseCreatedConsumer
**External Integrations**: E-conomic API  
**Coupling Score**: 0.5 (moderate - depends on users, AI validation)  
**Notes**: Event-driven with AI validation

### 13. Knowledge Management
**Path Pattern**: `/courses/*`, `/certifications/*`, `/conferences/*`  
**Primary Resources**: CourseResource, ConferenceResource, KnowledgeResource, FaqResource  
**Entities**: Course, Conference, ConferenceParticipant, Certification, UserCertification, Faq, ProjectDescription  
**Services**: CourseService, ConferenceService, CertificationService, FaqService, ProjectDescriptionService  
**Endpoints**: 15+ REST endpoints
**Messaging**: 
- Events: CreateParticipantEvent, UpdateParticipantDataEvent, ChangeParticipantPhaseEvent
**Coupling Score**: 0.4 (low - mostly independent)

### 14. Sales Management
**Path Pattern**: `/sales/*`  
**Primary Resource**: SalesResource  
**Entities**: SalesLead, SalesLeadConsultant, SalesCoffeeDate  
**Services**: SalesService  
**Endpoints**: 8+ REST endpoints
- GET /sales/leads, /sales/coffee-dates
- POST /sales/leads
**Coupling Score**: 0.5 (moderate - linked to users, contracts)

### 15. Finance & Accounting
**Path Pattern**: `/finance/*`, `/accounting/*`  
**Primary Resources**: FinanceResource, AccountingResource, DanlonResource  
**Entities**: Finance, FinanceDetails, AccountingAccount, AccountingCategory  
**Services**: FinanceService, EconomicsService, IntercompanyCalcService  
**Endpoints**: 20+ REST endpoints
**External Integrations**: E-conomic API  
**Transactions**: 6+ @Transactional methods  
**Coupling Score**: 0.7 (high - financial calculations across entities)

### 16. Team & Role Management
**Path Pattern**: `/teams/*`, `/roles/*`  
**Primary Resources**: TeamResource, TeamRoleResource, RoleResource  
**Entities**: Team, TeamRole, Role  
**Services**: TeamService, TeamRoleService, RoleService  
**Endpoints**: 10 REST endpoints
**Coupling Score**: 0.5 (moderate - linked to users, projects)

### 17. Vacation & Transportation
**Path Pattern**: `/users/{id}/vacation/*`, `/users/{id}/transportation/*`  
**Primary Resources**: VacationResource, TransportationRegistrationResource  
**Entities**: Vacation, TransportationRegistration  
**Services**: VacationService, TransportationRegistrationService  
**Endpoints**: 8 REST endpoints
**Transactions**: 4 @Transactional methods  
**Coupling Score**: 0.3 (low - user-specific)

### 18. Lunch Ordering
**Path Pattern**: `/lunch/*`  
**Primary Resources**: MenuResource, MealPlanResource, MealChoiceResource, MealBufferResource  
**Entities**: MealPlan, MealPlanUser, MealChoice, MealBuffer  
**Services**: MenuService, SummaryService  
**Endpoints**: 8 REST endpoints
**Transactions**: 4 @Transactional methods  
**Coupling Score**: 0.2 (low - independent subsystem)  
**Notes**: Self-contained, good migration candidate

### 19. Communication Services
**Path Pattern**: `/mail/*`, `/slack/*`  
**Primary Resources**: MailResource, SlackResource  
**Entities**: TrustworksMail, BulkEmailJob, BulkEmailRecipient  
**Services**: BulkEmailService, SlackService  
**Endpoints**: 6 REST endpoints
**Coupling Score**: 0.3 (low - integration layer)  
**Notes**: Already async-friendly (email/messaging)

### 20. File Management
**Path Pattern**: `/files/*`  
**Primary Resources**: FileResource, UserDocumentResource  
**Entities**: File  
**Services**: S3FileService, PhotoService  
**Endpoints**: 10+ REST endpoints
**External Integrations**: AWS S3  
**Coupling Score**: 0.3 (low - storage layer)  
**Notes**: Good async candidate (I/O bound)

## Feature Coupling Matrix

| Feature | Users | Contracts | Work | Invoices | Clients |
|---------|-------|-----------|------|----------|---------|
| Invoices | High | High | High | - | High |
| Bonus | High | Medium | High | High | Low |
| Revenue | High | High | High | Medium | Medium |
| Utilization | High | High | High | Low | Low |
| Budget | High | High | High | Low | Low |
| Expense | High | Low | Low | Low | Low |
| Lunch | High | Low | Low | Low | Low |
| Files | Medium | Low | Low | Low | Low |

## Migration Priority Ranking

### Tier 1: Easy Wins (Low Coupling, High Async Benefit)
1. **Lunch Ordering** - Score: 95/100
   - Self-contained, low coupling (0.2)
   - Clear transactional boundaries
   - Good test candidate for migration
2. **File Management** - Score: 90/100
   - I/O bound operations
   - Already external (S3)
   - Low coupling (0.3)
3. **Communication Services** - Score: 88/100
   - Naturally async (email, Slack)
   - Low coupling (0.3)
   - Event-driven foundation

### Tier 2: High Value (Moderate Coupling, Performance Gains)
4. **Revenue Reporting** - Score: 85/100
   - Read-heavy, no transactions
   - Good caching opportunity
   - Moderate coupling (0.4)
5. **Availability Tracking** - Score: 82/100
   - Read-heavy with existing cache
   - Low coupling (0.4)
   - Aggregate calculations benefit from streams
6. **Utilization Tracking** - Score: 80/100
   - Read-heavy reporting
   - Complex calculations (stream-friendly)
   - Low coupling (0.4)
7. **Expense Management** - Score: 78/100
   - Already event-driven
   - AI validation is async-friendly
   - Moderate coupling (0.5)

### Tier 3: Strategic (Core Domain, Higher Complexity)
8. **Invoice Bonus** - Score: 75/100
   - Strong invariants (good aggregate)
   - Complex but well-bounded
   - Moderate-high coupling (0.7)
9. **Budget Management** - Score: 72/100
   - Event consumer present
   - Aggregate calculations
   - Moderate coupling (0.5)
10. **Vacation & Transportation** - Score: 70/100
    - User-specific, low coupling (0.3)
    - Clear boundaries
    - Simple transactions

### Tier 4: Complex Core (High Coupling, Requires Planning)
11. **Work Tracking** - Score: 65/100
    - High-volume transactions
    - High coupling (0.75)
    - Already has caching strategy
12. **Contract Management** - Score: 62/100
    - Business-critical validation
    - Moderate coupling (0.65)
    - Event-driven foundation
13. **Invoice Management** - Score: 60/100
    - Core business capability
    - Moderate coupling (0.6)
    - Complex pricing logic

### Tier 5: Later Stage (High Coupling, Synchronous Dependencies)
14. **User Management** - Score: 50/100
    - Central aggregate (high coupling 0.8)
    - Already event-driven
    - High transaction count
15. **Finance & Accounting** - Score: 48/100
    - High coupling (0.7)
    - External integrations (E-conomic)
    - Complex calculations

