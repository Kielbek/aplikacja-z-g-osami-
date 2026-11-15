/**
 * Portrety głosów z biblioteki (frontend/public/voices/<id>.webp + <id>-face.webp).
 * Nowy portret: dodaj pliki (512 px i kadr twarzy 160 px) oraz identyfikator głosu poniżej.
 * Głosy bez portretu (w tym własne głosy użytkowników) pokazują inicjały.
 */
const PORTRAITS = new Set([
  'pl-anna', 'pl-tomasz', 'pl-julia', 'pl-marek', 'pl-zofia', 'pl-kacper', 'pl-ewa', 'pl-adam',
  'en-claire', 'en-james', 'en-maya',
]);

export function hasPortrait(voiceId: string): boolean {
  return PORTRAITS.has(voiceId);
}

/** Kadr twarzy dla małych awatarów, pełny portret od 96 px. */
export function portraitUrl(voiceId: string, size: number): string | null {
  if (!PORTRAITS.has(voiceId)) return null;
  return size >= 96 ? `/voices/${voiceId}.webp` : `/voices/${voiceId}-face.webp`;
}
