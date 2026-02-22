import { Component, ChangeDetectionStrategy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';

/**
 * Beta Terms Component
 *
 * Terms and conditions for the Rentoza beta testing phase.
 * Clearly communicates expectations, limitations, and responsibilities
 * for beta testers.
 *
 * Supports Serbian (default) and English languages.
 *
 * Last updated: February 2026
 */
@Component({
  selector: 'app-beta-terms',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatDividerModule,
  ],
  templateUrl: './beta-terms.component.html',
  styleUrls: ['./beta-terms.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BetaTermsComponent {
  readonly lastUpdated = '2. februar 2026.';
  readonly lastUpdatedEn = 'February 2, 2026';
  readonly contactEmail = 'rentozzza@gmail.com';

  /** Current language: 'sr' for Serbian (default), 'en' for English */
  readonly currentLang = signal<'sr' | 'en'>('sr');

  toggleLanguage(): void {
    this.currentLang.update((lang) => (lang === 'sr' ? 'en' : 'sr'));
  }
}