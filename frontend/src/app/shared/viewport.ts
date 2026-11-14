import { signal } from '@angular/core';

/** Czy interfejs jest w trybie mobilnym (listy i menu jako panel wysuwany od dołu). */
const query = typeof window !== 'undefined' ? window.matchMedia('(max-width: 640px)') : null;
export const isMobile = signal(query?.matches ?? false);
query?.addEventListener('change', (e) => isMobile.set(e.matches));
