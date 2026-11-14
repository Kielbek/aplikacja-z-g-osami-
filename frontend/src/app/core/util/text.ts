/** Dzielenie tekstu na fragmenty do generowania. */

const SENTENCE = /[^.!?…]+(?:[.!?…]+["”»)]*|$)\s*/g;

export function splitParagraphs(text: string): string[] {
  return text
    .replace(/\r\n/g, '\n')
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter(Boolean);
}

export function splitSentences(text: string): string[] {
  const out = (text.replace(/\s+/g, ' ').match(SENTENCE) ?? []).map((s) => s.trim()).filter(Boolean);
  return out.length ? out : [text.trim()];
}

/**
 * Dzieli tekst na fragmenty: najpierw akapity, a zbyt długie akapity — po zdaniach,
 * grupując zdania tak, aby fragment nie przekraczał maxChars.
 */
export function splitIntoSegments(text: string, maxChars = 400): string[] {
  const result: string[] = [];
  for (const para of splitParagraphs(text)) {
    if (para.length <= maxChars) {
      result.push(para);
      continue;
    }
    let current = '';
    for (const sentence of splitSentences(para)) {
      if (current && (current + ' ' + sentence).length > maxChars) {
        result.push(current);
        current = sentence;
      } else {
        current = current ? `${current} ${sentence}` : sentence;
      }
    }
    if (current) result.push(current);
  }
  return result;
}

export function uuid(): string {
  return crypto.randomUUID();
}
