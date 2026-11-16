-- Automatyczne nazwy projektów (z pierwszego zdania tekstu), dopóki użytkownik nie nada własnej.
alter table projects add column name_custom boolean not null default false;
update projects set name_custom = true where name <> 'Projekt bez tytułu';

-- Rezerwacja limitu znaków na całe zadanie generowania (sprawdzana przed startem).
alter table generation_jobs add column total_characters integer not null default 0;
alter table generation_jobs add column processed_characters integer not null default 0;
