import { ChangeDetectionStrategy, Component, output } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';

export interface StarterPrompt {
  label: string;
  icon: string;
  /** Tekst dla jednego lektora (akapity oddzielone pustą linią). */
  text?: string;
  /** Rozmowa: kolejne kwestie z głosami. */
  dialog?: { voiceId: string; text: string }[];
}

export const STARTER_PROMPTS: StarterPrompt[] = [
  {
    label: 'Opowiedz historię',
    icon: 'book',
    text: 'Dawno temu, w małym miasteczku nad rzeką, mieszkał zegarmistrz, który potrafił naprawić każdy zegar — oprócz własnego.\n\nPewnego wieczoru, gdy na rynku gasły latarnie, ktoś cicho zapukał do jego drzwi.',
  },
  {
    label: 'Nagraj reklamę',
    icon: 'mic',
    text: 'Poniedziałek rano? Nie musi być szary! [pauza 0.5s] Kawiarnia Ziarno zaprasza na kawę z ziaren palonych na miejscu i drożdżówki prosto z pieca.\n\nUlica Długa 14. Otwarte codziennie od siódmej.',
  },
  {
    label: 'Przedstaw podcast',
    icon: 'voices',
    text: 'Cześć, witajcie w kolejnym odcinku podcastu „Po godzinach”.\n\nDziś porozmawiamy o tym, jak znaleźć czas na pasje, gdy kalendarz pęka w szwach — i dlaczego warto zacząć od dziesięciu minut dziennie.',
  },
  {
    label: 'Poprowadź lekcję',
    icon: 'layers',
    text: 'W tej lekcji poznasz trzy zasady bezpiecznego korzystania z poczty firmowej.\n\nPo pierwsze: nie otwieraj załączników od nieznanych nadawców. Po drugie: sprawdzaj adres, zanim klikniesz w link. Po trzecie: zgłaszaj podejrzane wiadomości działowi IT.',
  },
  {
    label: 'Poprowadź medytację',
    icon: 'sun',
    text: 'Usiądź wygodnie i zamknij oczy. [pauza 1s] Weź głęboki wdech… [pauza 1.5s] i powoli wypuść powietrze.\n\nPoczuj, jak z każdym oddechem napięcie odpływa z ramion. [pauza 2s] Jesteś tu i teraz.',
  },
  {
    label: 'Przeczytaj wiersz',
    icon: 'pencil',
    text: 'Wieczorem miasto cicho śpiewa,\nlatarnie szepczą do drzew.\nCzy słyszysz, jak wiatr się zatrzymuje? [pauza 1s]\n\nNa moście stoi stary zegar,\nco liczy kroki, nie godziny.',
  },
  {
    label: 'Rozmowa dwóch osób',
    icon: 'rows',
    dialog: [
      { voiceId: 'pl-anna', text: 'Słyszałeś, że w piątek otwierają nową kawiarnię na rogu?' },
      { voiceId: 'pl-tomasz', text: 'Tę z zielonym szyldem? Mówią, że mają najlepsze croissanty w mieście.' },
      { voiceId: 'pl-anna', text: 'To co, idziemy rano przed pracą?' },
      { voiceId: 'pl-tomasz', text: 'Jestem za. Ale ty stawiasz kawę!' },
    ],
  },
];

/** Podpowiedzi startowe dla pustego projektu — pojawiają się kolejno, znikają po rozpoczęciu pisania. */
@Component({
  selector: 'vc-starter-prompts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    :host { display: block; }
    h3 {
      margin: 0 0 12px;
      font-size: var(--text-sm);
      font-weight: 500;
      color: var(--text-3);
      animation: prompt-in 420ms var(--ease-out, cubic-bezier(0.2, 0.7, 0.2, 1)) both;
    }
    .chips { display: flex; flex-wrap: wrap; gap: 8px; }
    button {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      height: 36px;
      padding: 0 14px;
      border: 1px solid var(--border);
      border-radius: 10px;
      background: var(--surface);
      color: var(--text);
      font: inherit;
      font-size: var(--text-sm);
      font-weight: 500;
      cursor: pointer;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
      animation: prompt-in 420ms var(--ease-out, cubic-bezier(0.2, 0.7, 0.2, 1)) both;
      animation-delay: calc(80ms + var(--i) * 60ms);
      transition: border-color 120ms, background 120ms, transform 120ms;
      &:hover { border-color: var(--border-strong); background: var(--surface-hover); transform: translateY(-1px); }
      &:active { transform: none; }
      vc-icon { color: var(--text-2); }
    }
    @keyframes prompt-in {
      from { opacity: 0; transform: translateY(10px) scale(0.98); }
      to { opacity: 1; transform: none; }
    }
    @media (prefers-reduced-motion: reduce) {
      h3, button { animation: none; }
    }
  `,
  template: `
    <h3>Zacznij od</h3>
    <div class="chips">
      @for (p of prompts; track p.label; let i = $index) {
        <button type="button" [style.--i]="i" (click)="choose.emit(p)">
          <vc-icon [name]="p.icon" [size]="15" />{{ p.label }}
        </button>
      }
    </div>
  `,
})
export class StarterPromptsComponent {
  readonly choose = output<StarterPrompt>();
  protected prompts = STARTER_PROMPTS;
}
