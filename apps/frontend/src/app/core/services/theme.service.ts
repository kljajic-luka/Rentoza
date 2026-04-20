import { DOCUMENT } from '@angular/common';
import { Inject, Injectable, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark';

const THEME_STORAGE_KEY = 'rentoza.theme.preference';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<ThemeMode>('light');

  constructor(@Inject(DOCUMENT) private readonly document: Document) {
    const storedTheme = (localStorage.getItem(THEME_STORAGE_KEY) as ThemeMode | null) ?? 'light';
    this.setTheme(storedTheme);
  }

  toggle(): void {
    const nextTheme: ThemeMode = this.theme() === 'light' ? 'dark' : 'light';
    this.setTheme(nextTheme);
  }

  private setTheme(mode: ThemeMode): void {
    this.theme.set(mode);
    localStorage.setItem(THEME_STORAGE_KEY, mode);
    this.document.documentElement.classList.toggle('theme-dark', mode === 'dark');
  }
}