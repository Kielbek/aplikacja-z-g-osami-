/** Formatowanie wartości w interfejsie (PL). */

export function formatDuration(ms: number | null | undefined): string {
  if (!ms || ms < 0) return '0:00';
  const total = Math.round(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
}

export function formatSeconds(sec: number): string {
  return formatDuration(sec * 1000);
}

const nf = new Intl.NumberFormat('pl-PL');
export function formatNumber(n: number): string {
  return nf.format(n);
}

/** Polska odmiana liczebników: 1 fragment, 2 fragmenty, 5 fragmentów. */
export function plural(n: number, one: string, few: string, many: string): string {
  if (n === 1) return one;
  const d = n % 10;
  const t = n % 100;
  return d >= 2 && d <= 4 && (t < 12 || t > 14) ? few : many;
}

export function pluralN(n: number, one: string, few: string, many: string): string {
  return `${formatNumber(n)} ${plural(n, one, few, many)}`;
}

const dateFmt = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'short', year: 'numeric' });
const timeFmt = new Intl.DateTimeFormat('pl-PL', { hour: '2-digit', minute: '2-digit' });
const dayFmt = new Intl.DateTimeFormat('pl-PL', { weekday: 'long', day: 'numeric', month: 'long' });

export function formatDate(iso: string | null | undefined): string {
  return iso ? dateFmt.format(new Date(iso)) : '—';
}

export function formatTime(iso: string): string {
  return timeFmt.format(new Date(iso));
}

export function formatDay(iso: string): string {
  const d = new Date(iso);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return 'Dzisiaj';
  if (d.toDateString() === yesterday.toDateString()) return 'Wczoraj';
  const s = dayFmt.format(d);
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 60) return 'przed chwilą';
  if (diff < 3600) return `${Math.floor(diff / 60)} min temu`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} godz. temu`;
  if (diff < 86400 * 7) {
    const d = Math.floor(diff / 86400);
    return d === 1 ? 'wczoraj' : `${d} dni temu`;
  }
  return formatDate(iso);
}

/** Szacowany czas nagrania: ~14 znaków na sekundę przy tempie 1.0. */
export function estimateDurationMs(chars: number, speed = 1): number {
  return (chars / 14 / speed) * 1000;
}
