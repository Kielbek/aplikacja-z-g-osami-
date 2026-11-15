/**
 * Propozycje znaku VoiceCraft AI. Każdy znak jest rysowany na siatce 32×32,
 * w dwóch kolorach: `fg` (główny) i `ac` (akcent kobaltowy).
 *
 * Aby zmienić logo w całej aplikacji, zmień BRAND_LOGO poniżej
 * (oraz plik public/favicon.svg — gotowe wersje są w public/brand/).
 */
export type LogoVariant = 'wave-v' | 'caret' | 'bubble' | 'orbit';

export const BRAND_LOGO: LogoVariant = 'wave-v';

export interface LogoConcept {
  id: LogoVariant;
  name: string;
  idea: string;
  /** Zawartość SVG; `currentColor` = kolor główny, `var(--logo-ac)` = akcent. */
  mark: string;
}

export const LOGO_CONCEPTS: LogoConcept[] = [
  {
    id: 'wave-v',
    name: 'Fala V',
    idea: 'Pięć pasków korektora tworzy literę V — głos (Voice) zapisany falą dźwiękową. Najdłuższy, środkowy pasek jest wyróżniony.',
    mark: `<g stroke-linecap="round" stroke-width="2.6">
      <path d="M8 9.5v4" stroke="currentColor"/>
      <path d="M12 9.5v8" stroke="currentColor"/>
      <path d="M16 9.5v13" stroke="var(--logo-ac)"/>
      <path d="M20 9.5v8" stroke="currentColor"/>
      <path d="M24 9.5v4" stroke="currentColor"/>
    </g>`,
  },
  {
    id: 'caret',
    name: 'Kursor w głos',
    idea: 'Kursor tekstowy przechodzi w rosnącą falę — dosłownie „tekst na mowę”. Dobrze czytelny także w małym rozmiarze.',
    mark: `<g stroke-linecap="round" fill="none">
      <path d="M9 9v14M7 9h4M7 23h4" stroke="var(--logo-ac)" stroke-width="2.2"/>
      <path d="M15.5 14v4M20 11.5v9M24.5 8.5v15" stroke="currentColor" stroke-width="2.6"/>
    </g>`,
  },
  {
    id: 'bubble',
    name: 'Dymek',
    idea: 'Dymek rozmowy z falą w środku — komunikacja i mowa. Najbardziej „przyjazny” z wariantów.',
    mark: `<path d="M9.5 7.5h13a3.5 3.5 0 0 1 3.5 3.5v8a3.5 3.5 0 0 1-3.5 3.5h-6.2l-4.6 3.6a.6.6 0 0 1-1-.5v-3.1H9.5A3.5 3.5 0 0 1 6 19v-8a3.5 3.5 0 0 1 3.5-3.5Z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>
      <g stroke-linecap="round" stroke-width="2.2">
        <path d="M12 13.5v3" stroke="currentColor"/>
        <path d="M16 11.5v7" stroke="var(--logo-ac)"/>
        <path d="M20 13.5v3" stroke="currentColor"/>
      </g>`,
  },
  {
    id: 'orbit',
    name: 'Sygnał',
    idea: 'Okrąg przecięty sinusoidą — minimalistyczny, techniczny znak w duchu narzędzi typu Linear. Najbardziej abstrakcyjny.',
    mark: `<circle cx="16" cy="16" r="9.5" fill="none" stroke="currentColor" stroke-width="2"/>
      <path d="M4.5 16c2.3 0 3.2-5 5.75-5s3.45 10 5.75 10 3.2-10 5.75-10 3.45 5 5.75 5" fill="none" stroke="var(--logo-ac)" stroke-width="2.2" stroke-linecap="round"/>`,
  },
];

/** Pełny plik SVG (kafelek grafitowy) — używany do favicon i plików w public/brand. */
export function tileSvg(concept: LogoConcept, bg = '#0A0A0A', fg = '#A3A3A0', ac = '#FFFFFF'): string {
  const mark = concept.mark.replaceAll('currentColor', fg).replaceAll('var(--logo-ac)', ac);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="${bg}"/>${mark}</svg>`;
}
