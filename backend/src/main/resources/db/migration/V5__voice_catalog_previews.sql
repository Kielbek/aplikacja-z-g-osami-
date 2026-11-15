-- Katalog głosów VoxCPM2: wspólne nagranie wzorcowe (Master Prompt), domyślna instrukcja sterująca,
-- próbki odsłuchowe w każdym z 30 języków (pamięć podręczna generowanych próbek).

alter table voices add column reference_key varchar(255);
alter table voices add column default_instruction varchar(300);

-- Tryb „ultimate cloning” (prompt_wav_path + prompt_text): domyślnie wyłączony — natywny akcent języka docelowego
alter table projects add column ultimate_cloning boolean;
update projects set ultimate_cloning = false;
alter table projects alter column ultimate_cloning set not null;

create table voice_previews (
    voice_id    varchar(255) not null references voices (id) on delete cascade,
    language    varchar(8)   not null,
    engine_mode varchar(32)  not null,
    audio_file  varchar(255) not null,
    created_at  timestamp(6) with time zone not null,
    primary key (voice_id, language)
);
