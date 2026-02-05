import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { ThemeService } from '@core/services/theme.service';

@Component({
  selector: 'app-theme-toggle',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <button
      mat-icon-button
      type="button"
      (click)="toggleTheme()"
      [matTooltip]="tooltip()"
      aria-label="Toggle theme"
    >
      <mat-icon>{{ icon() }}</mat-icon>
    </button>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ThemeToggleComponent {
  private readonly themeService = inject(ThemeService);

  protected readonly icon = computed(() =>
    this.themeService.theme() === 'light' ? 'dark_mode' : 'light_mode'
  );
  protected readonly tooltip = computed(() =>
    this.themeService.theme() === 'light' ? 'Switch to dark mode' : 'Switch to light mode'
  );

  toggleTheme(): void {
    this.themeService.toggle();
  }
}
