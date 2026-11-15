/**
 * Portrety głosów z biblioteki (frontend/public/voices/<id>.webp + <id>-face.webp).
 * Nowy portret: dodaj pliki (512 px i kadr twarzy 160 px) oraz wpis głos → plik poniżej.
 * Głosy bez portretu (w tym własne głosy użytkowników) pokazują inicjały.
 */
/** Głos katalogu → plik portretu (kilka głosów o tej samej barwie dzieli postać). */
/** Głos katalogu → plik portretu. Każda postać występuje tylko raz. */
const PORTRAITS: Record<string, string> = {
  voice_warm_velvet: 'pl-anna',
  voice_warm_narrator: 'pl-tomasz',
  voice_kind_grandma: 'pl-zofia',
  voice_young_influencer: 'pl-julia',
  voice_radio_presenter_m: 'pl-marek',
  voice_gamer_hype: 'pl-kacper',
  voice_corporate_f: 'pl-ewa',
  voice_deep_trailer: 'pl-adam',
  voice_news_anchor_f: 'en-claire',
  voice_casual_podcast: 'en-james',
  voice_anime_sweet: 'en-maya',
};

export function hasPortrait(voiceId: string): boolean {
  return voiceId in PORTRAITS;
}

/** Kadr twarzy dla małych awatarów, pełny portret od 96 px. */
export function portraitUrl(voiceId: string, size: number): string | null {
  const file = PORTRAITS[voiceId];
  if (!file) return null;
  return size >= 96 ? `/voices/${file}.webp` : `/voices/${file}-face.webp`;
}
