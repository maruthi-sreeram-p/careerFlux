-- ===========================================================================
-- V26  Adapter definitions
--
-- Until now an adapter was a Java class: reading a new kind of board meant a
-- code change and a release. Most public job feeds are the same shape — one
-- GET returning JSON with an array of postings — and differ only in where the
-- array is and what each field is called. A portal admin can now describe such
-- a feed as data, and one generic adapter reads every definition.
--
-- WHAT A DEFINITION CANNOT DO
--
-- It names an HTTPS endpoint and JSON paths, nothing else: no method, headers,
-- credentials, body or script. Requests go through the same transport as the
-- built-in adapters (address validation, redirect checks, bounded reads, rate
-- limiting). A definition grants no permission: a source that uses it walks
-- the same classify, robots.txt and terms-review gate as any other.
--
-- Keys carry the 'custom-' prefix so a definition can never take the key of a
-- built-in adapter, and a source's adapter_key says which kind it uses.
-- ===========================================================================

CREATE TABLE adapter_definitions (
    id                    uuid          PRIMARY KEY,
    adapter_key           varchar(64)   NOT NULL,
    display_name          varchar(120)  NOT NULL,
    description           varchar(600),
    documentation_url     varchar(500),

    -- https://… with an optional {token}, filled from the source's external
    -- identifier.
    endpoint_template     varchar(500)  NOT NULL,

    -- Where the postings array is in the response; null means the response is
    -- the array. Field paths are read inside each posting.
    items_path            varchar(200),
    id_path               varchar(200)  NOT NULL,
    title_path            varchar(200)  NOT NULL,
    location_path         varchar(200),
    description_path      varchar(200),
    apply_url_path        varchar(200),
    -- Used when a posting carries no link of its own: https://… with {id}.
    apply_url_template    varchar(500),
    posted_at_path        varchar(200),
    employment_type_path  varchar(200),
    department_path       varchar(200),

    enabled               boolean       NOT NULL,
    created_by            varchar(200)  NOT NULL,
    updated_by            varchar(200)  NOT NULL,
    created_at            timestamp with time zone NOT NULL,
    updated_at            timestamp with time zone NOT NULL,

    CONSTRAINT uq_adapter_definitions_key UNIQUE (adapter_key),
    CONSTRAINT ck_adapter_definitions_key CHECK (adapter_key LIKE 'custom-%')
);
