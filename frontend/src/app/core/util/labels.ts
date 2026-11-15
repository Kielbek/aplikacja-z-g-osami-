import { Gender, ProjectStatus, SegmentStatus } from '../api/models';

/** 30 języków VoxCPM2 (oraz kilka dodatkowych etykiet dla starszych danych). */
export const LANGUAGES: Record<string, string> = {
  pl: 'Polski',
  en: 'Angielski',
  de: 'Niemiecki',
  fr: 'Francuski',
  es: 'Hiszpański',
  it: 'Włoski',
  pt: 'Portugalski',
  nl: 'Niderlandzki',
  da: 'Duński',
  sv: 'Szwedzki',
  no: 'Norweski',
  fi: 'Fiński',
  ru: 'Rosyjski',
  el: 'Grecki',
  tr: 'Turecki',
  ar: 'Arabski',
  he: 'Hebrajski',
  hi: 'Hindi',
  id: 'Indonezyjski',
  ms: 'Malajski',
  tl: 'Tagalski',
  vi: 'Wietnamski',
  th: 'Tajski',
  lo: 'Laotański',
  km: 'Khmerski',
  my: 'Birmański',
  zh: 'Chiński',
  ja: 'Japoński',
  ko: 'Koreański',
  sw: 'Suahili',
  cs: 'Czeski',
  uk: 'Ukraiński',
};

/** Języki obsługiwane przez VoxCPM2 (zgodnie z silnikiem). */
export const VOXCPM2_LANGUAGES = ['pl', 'en', 'de', 'fr', 'es', 'it', 'pt', 'nl', 'da', 'sv', 'no', 'fi', 'ru', 'el', 'tr',
  'ar', 'he', 'hi', 'id', 'ms', 'tl', 'vi', 'th', 'lo', 'km', 'my', 'zh', 'ja', 'ko', 'sw'];

export const languageLabel = (code: string) => LANGUAGES[code] ?? code.toUpperCase();

/** Kraj (ISO 3166-1 alpha-2) reprezentujący język — do flagi przy nazwie języka. */
const LANGUAGE_COUNTRY: Record<string, string> = {
  pl: 'pl', en: 'gb', de: 'de', fr: 'fr', es: 'es', it: 'it', pt: 'pt', nl: 'nl', cs: 'cz', sk: 'sk', uk: 'ua',
  ru: 'ru', ja: 'jp', zh: 'cn', ko: 'kr', ar: 'sa', hi: 'in', tr: 'tr', sv: 'se', da: 'dk', fi: 'fi', no: 'no',
  nb: 'no', el: 'gr', he: 'il', id: 'id', ms: 'my', th: 'th', vi: 'vn', hu: 'hu', ro: 'ro', bg: 'bg', hr: 'hr',
  sr: 'rs', sl: 'si', lt: 'lt', lv: 'lv', et: 'ee', fa: 'ir', ur: 'pk', bn: 'bd', tl: 'ph', sw: 'ke', ca: 'es-ct',
  my: 'mm', km: 'kh', lo: 'la',
};

/** Głosy z akcentem innym niż domyślny kraj języka. */
const VOICE_COUNTRY: Record<string, string> = {};

export function flagCountry(lang: string, voiceId?: string | null): string | null {
  if (voiceId && VOICE_COUNTRY[voiceId]) return VOICE_COUNTRY[voiceId];
  return LANGUAGE_COUNTRY[(lang ?? '').toLowerCase()] ?? null;
}

/** Opcje listy języków dla vc-select (polski zawsze na górze). */
export function languageOptions(codes: string[]): { value: string; label: string; hint: string; flag: string | null }[] {
  return [...codes]
    .sort((a, b) => (a === 'pl' ? -1 : b === 'pl' ? 1 : languageLabel(a).localeCompare(languageLabel(b), 'pl')))
    .map((c) => ({ value: c, label: languageLabel(c), hint: c.toUpperCase(), flag: c }));
}

/** Kategorie zastosowań biblioteki (catalog.json). */
export const CATEGORIES: { id: string; label: string }[] = [
  { id: 'audiobooks', label: 'Audiobooki i Opowieści' },
  { id: 'social_media', label: 'Social Media i Marketing' },
  { id: 'media_business', label: 'Media i Biznes' },
  { id: 'gaming_animation', label: 'Gry i Animacje' },
  { id: 'wellness_relax', label: 'Relaks i Medytacja' },
  // starsze kategorie (własne głosy utworzone wcześniej)
  { id: 'natural', label: 'Naturalny' },
  { id: 'professional', label: 'Profesjonalny' },
  { id: 'energetic', label: 'Energiczny' },
  { id: 'calm', label: 'Spokojny' },
  { id: 'narrative', label: 'Narracyjny' },
  { id: 'advertising', label: 'Reklamowy' },
];

/** Kategorie widoczne w filtrach biblioteki. */
export const LIBRARY_CATEGORIES = CATEGORIES.slice(0, 5);

/** Persona głosu: Kobieta / Mężczyzna / Dziecko / Starzec / Staruszka. */
export function personaLabel(gender: string | null | undefined, age: number | null | undefined): string {
  if (age != null && age < 14) return 'Dziecko';
  if (age != null && age >= 65) return gender === 'female' ? 'Staruszka' : gender === 'male' ? 'Starzec' : 'Senior';
  return gender === 'female' ? 'Kobieta' : gender === 'male' ? 'Mężczyzna' : 'Neutralny';
}

export const categoryLabel = (id: string) => CATEGORIES.find((c) => c.id === id)?.label ?? id;

export const GENDERS: Record<Gender, string> = { female: 'Kobiecy', male: 'Męski', neutral: 'Neutralny' };

export const genderLabel = (g: Gender | null | undefined) => (g ? GENDERS[g] : '—');

export const SEGMENT_STATUS: Record<SegmentStatus, { label: string; tone: string }> = {
  empty: { label: 'Pusty', tone: '' },
  draft: { label: 'Do wygenerowania', tone: '' },
  ready: { label: 'Gotowy', tone: 'success' },
  stale: { label: 'Nieaktualny', tone: 'warning' },
  error: { label: 'Błąd', tone: 'danger' },
};

export const PROJECT_STATUS: Record<ProjectStatus, { label: string; tone: string }> = {
  empty: { label: 'Pusty', tone: '' },
  draft: { label: 'Szkic', tone: '' },
  partial: { label: 'Częściowo', tone: 'warning' },
  ready: { label: 'Gotowy', tone: 'success' },
  error: { label: 'Błąd', tone: 'danger' },
};

/** Emocja = control_instruction przekazywana do VoxCPM2 (wartość) z polską etykietą. */
export const EMOTIONS: { label: string; value: string }[] = [
  { label: 'Neutralnie', value: 'Neutral' },
  { label: 'Radośnie', value: 'Happy' },
  { label: 'Szeptem', value: 'Whisper' },
  { label: 'Spokojnie', value: 'Calm' },
  { label: 'Smutno', value: 'Sad' },
  { label: 'Z ekscytacją', value: 'Excited' },
  { label: 'Poważnie', value: 'Serious' },
  { label: 'Groźnie', value: 'Menacing' },
];

export const STYLE_PRESETS: { label: string; value: string }[] = [
  { label: 'Naturalnie', value: 'naturalnie, swobodnie, jak w rozmowie' },
  { label: 'Narracja', value: 'spokojna narracja, wyraźna dykcja, równe tempo' },
  { label: 'Prezentacja', value: 'rzeczowo, pewnie, profesjonalnie' },
  { label: 'Reklama', value: 'energicznie, z uśmiechem, dynamicznie' },
  { label: 'Ciepło', value: 'ciepło, łagodnie, przyjaźnie' },
];

/** Stały, spokojny odcień awatara na podstawie identyfikatora głosu. */
const AVATAR_TONES = [
  ['#e8ecf6', '#2c4ba3'],
  ['#eef1ec', '#3e6b4f'],
  ['#f5efe8', '#8a5a2b'],
  ['#efedf3', '#56507a'],
  ['#ecf1f3', '#2f6274'],
  ['#f4ecec', '#8a3f3f'],
  ['#f1f0ea', '#5f5a3a'],
];

export function avatarStyle(id: string): Record<string, string> {
  let h = 0;
  for (const c of id) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  const [bg, fg] = AVATAR_TONES[h % AVATAR_TONES.length];
  return { '--avatar-bg': bg, '--avatar-fg': fg };
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]!.toUpperCase())
    .join('');
}
