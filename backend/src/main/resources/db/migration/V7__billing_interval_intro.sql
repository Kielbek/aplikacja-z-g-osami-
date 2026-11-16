-- Rozliczenie miesięczne / roczne i jednorazowa zniżka na pierwszy miesiąc.
alter table accounts add column billing_interval varchar(8);
alter table accounts add column intro_offer_used boolean not null default false;
