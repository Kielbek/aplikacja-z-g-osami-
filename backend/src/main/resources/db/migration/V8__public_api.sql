-- Publiczne API: klucze, zadania w tle (z webhookami) i przypisanie zużycia do klucza.

create table api_keys (
    id           varchar(64)  not null primary key,
    user_id      varchar(255) not null references users (id),
    name         varchar(100) not null,
    -- Początek klucza do rozpoznania w interfejsie; sam klucz nie jest przechowywany (tylko skrót SHA-256)
    prefix       varchar(24)  not null,
    key_hash     varchar(64)  not null unique,
    created_at   timestamp(6) with time zone not null,
    last_used_at timestamp(6) with time zone,
    revoked_at   timestamp(6) with time zone
);
create index idx_api_keys_user on api_keys (user_id);

create table api_jobs (
    id              varchar(64)   not null primary key,
    user_id         varchar(255)  not null references users (id),
    api_key_id      varchar(64),
    status          varchar(16)   not null,
    text            text          not null,
    voice_id        varchar(255)  not null,
    language        varchar(16)   not null,
    output_format   varchar(8)    not null,
    settings        text          not null,
    characters      integer       not null,
    idempotency_key varchar(255),
    callback_url    varchar(2048),
    audio_file      varchar(255),
    duration_ms     bigint        not null default 0,
    engine_mode     varchar(32),
    error_code      varchar(64),
    error_message   text,
    webhook_status  varchar(16),
    webhook_attempts integer      not null default 0,
    created_at      timestamp(6) with time zone not null,
    started_at      timestamp(6) with time zone,
    finished_at     timestamp(6) with time zone,
    constraint uq_api_jobs_idempotency unique (user_id, idempotency_key)
);
create index idx_api_jobs_user_created on api_jobs (user_id, created_at);
create index idx_api_jobs_status on api_jobs (status);

alter table usage_events add column api_key_id varchar(64);

-- Sekret do podpisywania webhooków (HMAC-SHA256), widoczny dla właściciela w ustawieniach API
alter table accounts add column api_webhook_secret varchar(80);
