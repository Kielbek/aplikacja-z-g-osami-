import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { LogoComponent } from '../../shared/brand/logo.component';
import { LEGAL_VERSION, OPERATOR } from './legal.config';

export { LEGAL_VERSION } from './legal.config';

type Doc = 'terms' | 'privacy';

const TOC: Record<Doc, { id: string; label: string }[]> = {
  terms: [
    { id: 't1', label: '§1 Postanowienia ogólne' },
    { id: 't2', label: '§2 Definicje' },
    { id: 't3', label: '§3 Wymagania techniczne' },
    { id: 't4', label: '§4 Konto' },
    { id: 't5', label: '§5 Zakres usług' },
    { id: 't6', label: '§6 Plany, ceny i płatności' },
    { id: 't7', label: '§7 Prawo odstąpienia' },
    { id: 't8', label: '§8 Treści i prawa' },
    { id: 't9', label: '§9 Oznaczanie treści AI' },
    { id: 't10', label: '§10 Klonowanie głosu' },
    { id: 't11', label: '§11 Zakazane działania' },
    { id: 't12', label: '§12 Zgłaszanie nielegalnych treści' },
    { id: 't13', label: '§13 API' },
    { id: 't14', label: '§14 Dostępność usług' },
    { id: 't15', label: '§15 Odpowiedzialność' },
    { id: 't16', label: '§16 Reklamacje' },
    { id: 't17', label: '§17 Rozwiązanie umowy' },
    { id: 't18', label: '§18 Zmiany regulaminu' },
    { id: 't19', label: '§19 Postanowienia końcowe' },
    { id: 'zal1', label: 'Załącznik: formularz odstąpienia' },
  ],
  privacy: [
    { id: 'p0', label: 'W skrócie' },
    { id: 'p1', label: '1. Administrator' },
    { id: 'p2', label: '2. Cele i podstawy' },
    { id: 'p3', label: '3. Okres przechowywania' },
    { id: 'p4', label: '4. Odbiorcy danych' },
    { id: 'p5', label: '5. Przekazywanie poza EOG' },
    { id: 'p6', label: '6. Sztuczna inteligencja' },
    { id: 'p7', label: '7. Twoje prawa' },
    { id: 'p8', label: '8. Dobrowolność' },
    { id: 'p9', label: '9. Bezpieczeństwo' },
    { id: 'p10', label: '10. Cookies i pamięć przeglądarki' },
    { id: 'p11', label: '11. Zmiany polityki' },
  ],
};

/**
 * Regulamin oraz polityka prywatności i cookies (strony publiczne).
 * Dane usługodawcy pochodzą z legal.config.ts. Nawigacja po sekcjach jest ręczna — aplikacja ma
 * <base href="/">, więc zwykłe odnośniki „#sekcja” prowadziłyby do strony głównej.
 */
@Component({
  selector: 'vc-legal-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, LogoComponent],
  styleUrl: './legal.page.scss',
  templateUrl: './legal.page.html',
})
export class LegalPage {
  private route = inject(ActivatedRoute);
  private host = inject(ElementRef<HTMLElement>);
  private destroyRef = inject(DestroyRef);

  protected o = OPERATOR;
  protected version = LEGAL_VERSION;
  protected doc = toSignal(this.route.data.pipe(map((d) => d['doc'] as Doc)), { initialValue: 'terms' as Doc });
  protected title = computed(() => (this.doc() === 'terms' ? 'Regulamin świadczenia usług' : 'Polityka prywatności i cookies'));
  protected toc = computed(() => TOC[this.doc()]);
  protected active = signal('');

  constructor() {
    let observer: IntersectionObserver | null = null;
    const observe = () => {
      observer?.disconnect();
      const sections = Array.from((this.host.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[data-sec]'));
      const visible = new Set<string>();
      observer = new IntersectionObserver(
        (entries) => {
          for (const e of entries) {
            if (e.isIntersecting) visible.add(e.target.id);
            else visible.delete(e.target.id);
          }
          const first = sections.find((s) => visible.has(s.id));
          if (first) this.active.set(first.id);
        },
        { rootMargin: '-80px 0px -65% 0px' },
      );
      sections.forEach((s) => observer!.observe(s));
    };
    afterNextRender(() => {
      observe();
      const hash = decodeURIComponent(location.hash.slice(1));
      if (hash) [60, 300].forEach((ms) => setTimeout(() => this.scrollTo(hash), ms));
    });
    // Przełączenie regulamin ↔ polityka (ten sam komponent): nowe sekcje do obserwacji
    effect(() => {
      this.doc();
      this.active.set(TOC[this.doc()][0].id);
      setTimeout(observe, 0);
    });
    this.destroyRef.onDestroy(() => observer?.disconnect());
  }

  go(id: string, event?: Event) {
    event?.preventDefault();
    this.scrollTo(id);
    history.replaceState(history.state, '', `${location.pathname}#${id}`);
  }

  /** Odnośniki „#sekcja” w treści dokumentu. */
  onContentClick(event: MouseEvent) {
    const href = (event.target as HTMLElement).closest('a')?.getAttribute('href');
    if (href?.startsWith('#')) this.go(href.slice(1), event);
  }

  print() {
    window.print();
  }

  protected link(id: string) {
    return `${this.doc() === 'terms' ? '/terms' : '/privacy'}#${id}`;
  }

  private scrollTo(id: string) {
    const el = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>(`#${CSS.escape(id)}`);
    if (!el) return;
    el.scrollIntoView({ behavior: 'auto', block: 'start' });
    this.active.set(id);
  }
}
