/**
 * Dane usługodawcy i dostawców używane w regulaminie i polityce prywatności.
 * UZUPEŁNIJ przed udostępnieniem serwisu — wartości w nawiasach kwadratowych są widoczne dla użytkowników.
 * Po zmianie treści dokumentów zmień też LEGAL_VERSION (frontend) i LegalDocuments.TERMS_VERSION (backend).
 */
export const OPERATOR = {
  /** Pełna nazwa (firma) usługodawcy, np. „Jan Kowalski prowadzący działalność gospodarczą pod firmą Narrivy Jan Kowalski”. */
  name: '[Pełna nazwa usługodawcy]',
  address: '[ulica i numer, kod pocztowy, miejscowość]',
  nip: '[NIP]',
  regon: '[REGON]',
  /** Np. „wpisany do CEIDG” albo „wpisana do rejestru przedsiębiorców KRS pod numerem …, kapitał zakładowy …”. */
  register: '[wpis do CEIDG / KRS nr …]',
  /** Kontakt ogólny i reklamacje. */
  email: '[kontakt@twoja-domena.pl]',
  /** Sprawy danych osobowych. */
  privacyEmail: '[prywatnosc@twoja-domena.pl]',
  /** Zgłoszenia nielegalnych treści i nadużyć (pojedynczy punkt kontaktowy DSA). */
  abuseEmail: '[naduzycia@twoja-domena.pl]',
  /** Inspektor ochrony danych — jeśli wyznaczono (w przeciwnym razie zostaw null). */
  dpo: null as string | null,
  /** Dostawca serwerów aplikacji i bazy danych. */
  hosting: '[dostawca hostingu, kraj przetwarzania]',
  /** Dostawca serwerów z kartami graficznymi (synteza mowy). */
  gpu: '[dostawca serwerów GPU, kraj przetwarzania]',
  /** Dostawca wysyłki e-maili. */
  mail: '[dostawca poczty e-mail, kraj przetwarzania]',
  /** Biuro rachunkowe / system księgowy. */
  accounting: '[biuro rachunkowe lub system księgowy]',
  /** Domena serwisu. */
  domain: '[twoja-domena.pl]',
};

/** Wersja dokumentów — taka sama jak LegalDocuments.TERMS_VERSION w backendzie. */
export const LEGAL_VERSION = '2026-11-16';
