-- Limit miejsca na nagrania zamiast limitu liczby projektów: rejestr plików audio z właścicielem i rozmiarem.
create table stored_files (
    file_key   varchar(255) not null primary key,
    user_id    varchar(255),
    bytes      bigint       not null,
    created_at timestamp(6) with time zone not null
);
create index idx_stored_files_user on stored_files (user_id);
