import { Component, ChangeDetectionStrategy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';

/**
 * Privacy Policy Component
 *
 * GDPR-compliant privacy policy for the Rentoza beta.
 * Provides clear, human-readable information about data collection,
 * processing, and user rights.
 *
 * Supports Serbian (default) and English languages.
 *
 * Last updated: February 2026
 */
@Component({
  selector: 'app-privacy-policy',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatDividerModule,
  ],
  templateUrl: './privacy-policy.component.html',
  styleUrls: ['./privacy-policy.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrivacyPolicyComponent {
  readonly lastUpdated = '2. februar 2026.';
  readonly lastUpdatedEn = 'February 2, 2026';
  readonly contactEmail = 'privacy@rentoza.rs';
  readonly dataRetentionMonths = 12;

  /** Current language: 'sr' for Serbian (default), 'en' for English */
  readonly currentLang = signal<'sr' | 'en'>('sr');

  toggleLanguage(): void {
    this.currentLang.update((lang) => (lang === 'sr' ? 'en' : 'sr'));
  }
}
