import { Gender, ProjectStatus, SegmentStatus } from '../api/models';

export const LANGUAGES: Record<string, string> = {
  pl: 'Polski',
  en: 'Angielski',
  de: 'Niemiecki',
  fr: 'Francuski',
  es: 'Hiszpański',
  it: 'Włoski',
  pt: 'Portugalski',
  nl: 'Niderlandzki',
  cs: 'Czeski',
  uk: 'Ukraiński',
  ja: 'Japoński',
  zh: 'Chiński',
  ko: 'Koreański',
};

export const languageLabel = (code: string) => LANGUAGES[code] ?? code.toUpperCase();

/** Opcje listy języków dla vc-select (polski zawsze na górze). */
export function languageOptions(codes: string[]): { value: string; label: string; hint: string }[] {
  return [...codes]
    .sort((a, b) => (a === 'pl' ? -1 : b === 'pl' ? 1 : languageLabel(a).localeCompare(languageLabel(b), 'pl')))
    .map((c) => ({ value: c, label: languageLabel(c), hint: c.toUpperCase() }));
}

export const CATEGORIES: { id: string; label: string }[] = [
  { id: 'natural', label: 'Naturalny' },
  { id: 'professional', label: 'Profesjonalny' },
  { id: 'energetic', label: 'Energiczny' },
  { id: 'calm', label: 'Spokojny' },
  { id: 'narrative', label: 'Narracyjny' },
  { id: 'advertising', label: 'Reklamowy' },
];

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

export const EMOTIONS = ['neutralny', 'radosny', 'spokojny', 'poważny', 'smutny', 'energiczny'];

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
