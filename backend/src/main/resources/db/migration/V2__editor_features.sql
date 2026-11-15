-- Funkcje edytora: głos per fragment, wersje nagrań, słownik wymowy, normalizacja głośności eksportu.

alter table segments add column voice_id varchar(255);

alter table generation_history add column segment_hash varchar(64);
alter table generation_history add column segment_text varchar(5000);
create index idx_history_user_segment on generation_history (user_id, segment_id, created_at);

alter table accounts add column export_loudness varchar(16);
update accounts set export_loudness = '-16';

create table pronunciation_entries (
    id             varchar(255) not null primary key,
    owner_id       varchar(255) not null references users (id),
    project_id     varchar(255) references projects (id) on delete cascade,
    term           varchar(200) not null,
    replacement    varchar(500) not null,
    case_sensitive boolean      not null,
    created_at     timestamp(6) with time zone
);
create index idx_pronunciation_owner_project on pronunciation_entries (owner_id, project_id);
