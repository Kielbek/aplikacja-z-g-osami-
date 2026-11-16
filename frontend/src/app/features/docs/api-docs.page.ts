import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { LogoComponent } from '../../shared/brand/logo.component';
import { CodeBlockComponent, CodeSample } from '../../shared/code-block.component';
import { ERRORS, endpoints, md } from './api-docs.data';
import { apiBase, quickStart, webhookSamples } from './api-snippets';

interface NavItem { id: string; label: string; method?: 'GET' | 'POST' }

/**
 * Publiczna dokumentacja API (bez logowania). Maszynowy opis: /v1/openapi.json.
 * Nawigacja po sekcjach jest obsługiwana ręcznie: aplikacja ma <base href="/">, więc zwykłe
 * odnośniki „#sekcja” prowadziłyby do strony głównej.
 */
@Component({
  selector: 'vc-api-docs-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, LogoComponent, CodeBlockComponent],
  templateUrl: './api-docs.page.html',
  styleUrl: './api-docs.page.scss',
})
export class ApiDocsPage {
  protected auth = inject(AuthService);
  private host = inject(ElementRef<HTMLElement>);
  private destroyRef = inject(DestroyRef);

  protected base = apiBase();
  protected endpoints = endpoints(this.base);
  protected errors = ERRORS;
  protected quick = quickStart();
  protected webhooks = webhookSamples();
  protected md = md;
  protected active = signal('wprowadzenie');
  protected navOpen = signal(false);
  /** Podczas przewijania z kliknięcia podświetlenie nie podąża za mijanymi sekcjami. */
  private spyLockedUntil = 0;

  protected nav: { group: string; items: NavItem[] }[] = [
    { group: 'Pierwsze kroki', items: [
      { id: 'wprowadzenie', label: 'Wprowadzenie' },
      { id: 'uwierzytelnianie', label: 'Uwierzytelnianie' },
      { id: 'szybki-start', label: 'Szybki start' },
    ] },
    { group: 'Endpointy', items: this.endpoints.map((e) => ({ id: e.id, label: e.title, method: e.method })) },
    { group: 'Przewodniki', items: [
      { id: 'dlugie-teksty', label: 'Długie teksty' },
      { id: 'webhooki', label: 'Webhooki' },
      { id: 'pauzy', label: 'Pauzy, wymowa i styl' },
      { id: 'limity', label: 'Limity' },
      { id: 'bledy', label: 'Błędy' },
      { id: 'ponawianie', label: 'Ponawianie i idempotencja' },
      { id: 'bezpieczenstwo', label: 'Bezpieczeństwo kluczy' },
      { id: 'wersje', label: 'Wersje i zmiany' },
    ] },
  ];

  protected authHeader: CodeSample[] = [{ label: 'HTTP', code: 'Authorization: Bearer nrv_live_…\n\n# lub\nX-API-Key: nrv_live_…' }];
  protected errorExample: CodeSample[] = [{ label: 'JSON', code: `{
  "error": {
    "code": "quota_exceeded",
    "message": "Wykorzystano miesięczny limit znaków planu Pro (200 000). …",
    "request_id": "req_8f3a2c1b9d0e4f5a6b7c8d9e"
  }
}` }];
  protected webhookExample: CodeSample[] = [{ label: 'HTTP', code: `POST /webhooks/narrivy HTTP/1.1
Content-Type: application/json
User-Agent: Narrivy-Webhooks/1.0
Narrivy-Signature: t=1763284300,v1=5f2b9c0d…

{
  "id": "evt_3f2a9c0e7b1d4e5f6a7b8c9d_0",
  "type": "job.succeeded",
  "created_at": "2026-11-16T09:31:40Z",
  "data": {
    "id": "job_3f2a9c0e7b1d4e5f6a7b8c9d",
    "object": "text_to_speech_job",
    "status": "succeeded",
    "audio_url": "/v1/text-to-speech/jobs/job_3f2a9c0e7b1d4e5f6a7b8c9d/audio",
    …
  }
}` }];
  protected retrySample: CodeSample[] = [{ label: 'Python', code: `import time, uuid, requests

RETRY = {429, 500, 502, 503, 504}

def call(method, url, **kw):
    """Ponawia błędy przejściowe z odstępem z Retry-After (lub wykładniczym)."""
    for attempt in range(5):
        res = requests.request(method, url, timeout=180, **kw)
        if res.status_code not in RETRY:
            return res
        time.sleep(float(res.headers.get("Retry-After", 2 ** attempt)))
    return res

# Ten sam Idempotency-Key przy każdej próbie — zadanie powstanie tylko raz
key = str(uuid.uuid4())
job = call("POST", "${apiBase()}/v1/text-to-speech/jobs",
           headers={"Authorization": "Bearer nrv_live_…", "Idempotency-Key": key},
           json={"text": "…", "voice_id": "voice_warm_narrator"})` }];

  constructor() {
    afterNextRender(() => {
      const root = this.host.nativeElement as HTMLElement;
      const sections = Array.from(root.querySelectorAll<HTMLElement>('[data-section]'));
      const visible = new Set<string>();
      const observer = new IntersectionObserver(
        (entries) => {
          for (const e of entries) {
            const id = (e.target as HTMLElement).id;
            if (e.isIntersecting) visible.add(id);
            else visible.delete(id);
          }
          // Aktywna = pierwsza (najwyżej) widoczna sekcja w górnej części ekranu
          const first = sections.find((s) => visible.has(s.id));
          if (first && Date.now() > this.spyLockedUntil) this.active.set(first.id);
        },
        { rootMargin: '-72px 0px -60% 0px' },
      );
      sections.forEach((s) => observer.observe(s));
      this.destroyRef.onDestroy(() => observer.disconnect());

      // Wejście z adresem /docs/api#sekcja: router najpierw przewija na górę, więc przewijamy po nim
      const hash = decodeURIComponent(location.hash.slice(1));
      if (hash) [60, 300].forEach((ms) => setTimeout(() => this.scrollTo(hash, false), ms));
      const onHash = () => {
        const id = decodeURIComponent(location.hash.slice(1));
        // Router reaguje na zmianę adresu i przewija na górę — przewijamy po nim
        if (id) [60, 250].forEach((ms) => setTimeout(() => this.scrollTo(id, false), ms));
      };
      window.addEventListener('hashchange', onHash);
      this.destroyRef.onDestroy(() => window.removeEventListener('hashchange', onHash));
      const unlock = () => (this.spyLockedUntil = 0);
      window.addEventListener('scrollend', unlock);
      this.destroyRef.onDestroy(() => window.removeEventListener('scrollend', unlock));
    });
  }

  /** Przejście do sekcji bez opuszczania strony (adres zmienia się na /docs/api#sekcja). */
  go(id: string, event?: Event) {
    event?.preventDefault();
    this.navOpen.set(false);
    this.scrollTo(id, true);
    history.replaceState(history.state, '', `${location.pathname}#${id}`);
  }

  /** Odnośniki „#sekcja” w treści (także w opisach generowanych z danych). */
  onContentClick(event: MouseEvent) {
    const a = (event.target as HTMLElement).closest('a');
    const href = a?.getAttribute('href');
    if (href?.startsWith('#')) this.go(href.slice(1), event);
  }

  protected anchor(id: string) {
    return `/docs/api#${id}`;
  }

  protected methodClass(m: string) {
    return m === 'GET' ? 'get' : 'post';
  }

  private scrollTo(id: string, smooth: boolean) {
    const el = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>(`#${CSS.escape(id)}`);
    if (!el) return;
    // Daleki skok bez animacji (płynne przewijanie przez kilkanaście ekranów męczy wzrok)
    const far = Math.abs(el.getBoundingClientRect().top) > window.innerHeight * 3;
    this.spyLockedUntil = Date.now() + 1500;
    el.scrollIntoView({ behavior: smooth && !far && !matchMedia('(prefers-reduced-motion: reduce)').matches ? 'smooth' : 'auto', block: 'start' });
    this.active.set(id);
  }
}
