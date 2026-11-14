import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  afterNextRender,
  effect,
  input,
  output,
  viewChild,
} from '@angular/core';

/** Waveform rysowany na canvasie (ostre krawędzie na ekranach HiDPI), z postępem i znacznikami. */
@Component({
  selector: 'vc-waveform',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: 'slider',
    tabindex: '0',
    'aria-label': 'Pozycja odtwarzania',
    '[attr.aria-valuenow]': 'Math.round(progress() * 100)',
    'aria-valuemin': '0',
    'aria-valuemax': '100',
    '(pointerdown)': 'onDown($event)',
    '(keydown.arrowleft)': 'seek.emit(Math.max(0, progress() - 0.02))',
    '(keydown.arrowright)': 'seek.emit(Math.min(1, progress() + 0.02))',
  },
  styles: `
    :host { display: block; position: relative; height: 36px; cursor: pointer; touch-action: none; border-radius: 4px; }
    canvas { position: absolute; inset: 0; width: 100%; height: 100%; }
  `,
  template: `<canvas #canvas></canvas>`,
})
export class WaveformComponent implements OnDestroy {
  protected Math = Math;
  readonly peaks = input<number[]>([]);
  readonly progress = input(0);
  readonly markers = input<number[]>([]);
  readonly barWidth = input(2);
  readonly gap = input(1);
  readonly seek = output<number>();

  private canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private host: HTMLElement;
  private ro?: ResizeObserver;

  constructor(el: ElementRef<HTMLElement>) {
    this.host = el.nativeElement;
    afterNextRender(() => {
      this.ro = new ResizeObserver(() => this.draw());
      this.ro.observe(this.host);
      this.draw();
    });
    effect(() => {
      this.peaks();
      this.progress();
      this.markers();
      this.draw();
    });
  }

  ngOnDestroy() {
    this.ro?.disconnect();
  }

  protected onDown(e: PointerEvent) {
    const rect = this.host.getBoundingClientRect();
    const emit = (ev: PointerEvent) => this.seek.emit(Math.max(0, Math.min(1, (ev.clientX - rect.left) / rect.width)));
    emit(e);
    const move = (ev: PointerEvent) => emit(ev);
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  private draw() {
    const canvas = this.canvas()?.nativeElement;
    if (!canvas) return;
    const w = this.host.clientWidth;
    const h = this.host.clientHeight;
    if (!w || !h) return;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    const ctx = canvas.getContext('2d')!;
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    const style = getComputedStyle(this.host);
    const base = style.getPropertyValue('--wave').trim() || '#c9c6bf';
    const played = style.getPropertyValue('--wave-played').trim() || '#2c4ba3';
    const step = this.barWidth() + this.gap();
    const bars = Math.max(1, Math.floor(w / step));
    const peaks = this.peaks();
    const progressX = this.progress() * w;

    for (let i = 0; i < bars; i++) {
      let v: number;
      if (peaks.length) {
        const from = Math.floor((i / bars) * peaks.length);
        const to = Math.max(from + 1, Math.floor(((i + 1) / bars) * peaks.length));
        v = 0;
        for (let j = from; j < to; j++) v = Math.max(v, peaks[j] ?? 0);
      } else {
        v = 0.06;
      }
      const bh = Math.max(2, Math.pow(v, 0.8) * (h - 4));
      const x = i * step;
      ctx.fillStyle = x + this.barWidth() <= progressX ? played : base;
      ctx.beginPath();
      ctx.roundRect(x, (h - bh) / 2, this.barWidth(), bh, 1);
      ctx.fill();
    }

    // Granice fragmentów
    ctx.fillStyle = style.getPropertyValue('--text-4').trim() || '#a2a6ac';
    for (const m of this.markers()) {
      const x = Math.round(m * w);
      ctx.fillRect(x, 0, 1, 3);
      ctx.fillRect(x, h - 3, 1, 3);
    }
  }
}
