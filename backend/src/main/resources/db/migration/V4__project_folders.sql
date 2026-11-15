-- Foldery projektów (jeden poziom — bez folderów w folderach).
create table project_folders (
    id         varchar(255) not null primary key,
    owner_id   varchar(255) not null references users (id),
    name       varchar(80)  not null,
    created_at timestamp(6) with time zone not null
);
create index idx_project_folders_owner on project_folders (owner_id);

alter table projects add column folder_id varchar(255) references project_folders (id) on delete set null;
create index idx_projects_folder on projects (folder_id);
