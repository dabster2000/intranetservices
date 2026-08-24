-- Vacation ledger: split ferie / feriefridage day tracking with Danløn import
-- reconciliation. Replaces the never-launched `vacation` table (kept for now;
-- dropped in a later migration once the new module is verified in production).

-- Temporal accrual rates. Append-only; new rows must have a future
-- effective_from (enforced in the service layer — "forward-only").
create table vacation_policies
(
    uuid                        varchar(36)  not null primary key,
    effective_from              date         not null,
    ferie_days_per_month        decimal(4, 2) not null,
    feriefridage_days_per_month decimal(4, 2) not null,
    created_at                  datetime     not null default current_timestamp,
    created_by                  varchar(36)  not null,
    constraint uq_vacation_policies_effective_from unique (effective_from)
);

-- Seed: statutory samtidighedsferie rate (2.08/month = 25 days/year) plus the
-- contractual 6th week (0.42/month = 5 days/year) — together the 2.5/month
-- Danløn is configured with.
insert into vacation_policies (uuid, effective_from, ferie_days_per_month, feriefridage_days_per_month, created_by)
values ('6f0d1c2a-0000-4000-8000-000000000001', '2000-01-01', 2.08, 0.42, 'system');

-- The facts. Balances are computed, never stored. `days` is always positive
-- except for ADJUSTMENT entries, which are signed. `source_ref` is NOT NULL by
-- design: it participates in the dedup key (accrual → 'accrual:YYYY-MM',
-- import → batch uuid, HR letter → letter uuid, manual → generated uuid) and a
-- nullable column would disable the unique constraint in MariaDB.
create table vacation_ledger_entries
(
    uuid           varchar(36)   not null primary key,
    useruuid       varchar(36)   not null,
    ferieaar       smallint      not null,
    pool           varchar(16)   not null,
    entry_type     varchar(32)   not null,
    days           decimal(6, 2) not null,
    effective_date date          not null,
    source         varchar(16)   not null,
    source_ref     varchar(64)   not null,
    note           varchar(500)  null,
    created_at     datetime      not null default current_timestamp,
    created_by     varchar(36)   not null,
    constraint uq_vacation_ledger_entries_dedup
        unique (useruuid, ferieaar, pool, entry_type, effective_date, source_ref),
    index idx_vacation_ledger_entries_user (useruuid, ferieaar),
    index idx_vacation_ledger_entries_source_ref (source_ref)
);

-- One row per uploaded Danløn feriepengeforpligtelse CSV.
create table vacation_import_batches
(
    uuid            varchar(36)  not null primary key,
    companyuuid     varchar(36)  not null,
    filename        varchar(255) not null,
    as_of_date      date         not null,
    status          varchar(16)  not null,
    uploaded_by     varchar(36)  not null,
    uploaded_at     datetime     not null default current_timestamp,
    applied_at      datetime     null,
    applied_by      varchar(36)  null,
    row_count       int          not null default 0,
    matched_count   int          not null default 0,
    unmatched_count int          not null default 0,
    index idx_vacation_import_batches_company (companyuuid, uploaded_at)
);

-- Raw parsed rows, kept verbatim for audit. `raw_json` carries every parsed
-- column including the DKK amounts the system deliberately never interprets.
create table vacation_import_rows
(
    uuid         varchar(36)  not null primary key,
    batch_uuid   varchar(36)  not null,
    line_no      int          not null,
    danlon_name  varchar(255) not null,
    raw_json     text         not null,
    useruuid     varchar(36)  null,
    match_status varchar(16)  not null,
    constraint fk_vacation_import_rows_vacation_import_batches
        foreign key (batch_uuid) references vacation_import_batches (uuid) on delete cascade,
    index idx_vacation_import_rows_batch (batch_uuid)
);

-- Persisted Danløn-name → user mapping, written on every manual resolution so
-- the next upload auto-matches.
create table danlon_name_mappings
(
    uuid            varchar(36)  not null primary key,
    normalized_name varchar(255) not null,
    useruuid        varchar(36)  not null,
    created_at      datetime     not null default current_timestamp,
    created_by      varchar(36)  not null,
    constraint uq_danlon_name_mappings_name unique (normalized_name)
);

-- Vacation console page, after the Letters console (716) in the PEOPLE
-- section. HR and ADMIN only, matching the BFF gate — team leads see their
-- members' balances on the team dashboard instead.
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission,
     display_order, section, icon_name, is_external, external_url)
VALUES
    ('vacation-console', 'Vacation', 1, '/employee-management/vacation',
     'HR,ADMIN', NULL, 717, 'PEOPLE', 'TreePalm', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label       = VALUES(page_label),
    react_route      = VALUES(react_route),
    required_roles   = VALUES(required_roles),
    display_order    = VALUES(display_order),
    section          = VALUES(section),
    icon_name        = VALUES(icon_name);

-- Salary tab on the Settings page (ADMIN only): temporal vacation accrual
-- rates. The frontend maps page_key 'settings-salary' → SalarySettingsTab.
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission,
     display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-salary', 'Salary', 1, '/settings',
     'ADMIN', NULL, 115, 'SETTINGS', 'Banknote', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label       = VALUES(page_label),
    react_route      = VALUES(react_route),
    required_roles   = VALUES(required_roles),
    display_order    = VALUES(display_order),
    section          = VALUES(section),
    icon_name        = VALUES(icon_name);
