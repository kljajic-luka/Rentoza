import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ShortcutEntry } from '../../services/admin-keyboard.service';

@Component({
  selector: 'app-shortcut-help-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      <mat-icon class="title-icon">keyboard</mat-icon>
      Keyboard Shortcuts
    </h2>
    <mat-dialog-content>
      <div *ngFor="let category of categories" class="shortcut-category">
        <div class="category-label">{{ category }}</div>
        <div class="shortcut-row" *ngFor="let s of getByCategory(category)">
          <span class="shortcut-keys">
            <kbd *ngFor="let k of parseKeys(s.keys)">{{ k }}</kbd>
          </span>
          <span class="shortcut-desc">{{ s.description }}</span>
        </div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      h2 {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .title-icon {
        color: var(--icon-color, #593cfb);
      }
      .shortcut-category {
        margin-bottom: 16px;
      }
      .category-label {
        font-size: 11px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: var(--color-text-muted, #94a3b8);
        margin-bottom: 8px;
        padding-bottom: 4px;
        border-bottom: 1px solid var(--color-border-subtle, #e2e8f0);
      }
      .shortcut-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 6px 0;
      }
      .shortcut-keys {
        display: flex;
        gap: 4px;
        align-items: center;
      }
      kbd {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 26px;
        height: 26px;
        padding: 0 6px;
        background: var(--color-surface-muted, #f1f5f9);
        border: 1px solid var(--color-border-subtle, #e2e8f0);
        border-radius: 6px;
        font-family: inherit;
        font-size: 12px;
        font-weight: 600;
        color: var(--color-text-primary, #0f172a);
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
      }
      .shortcut-desc {
        font-size: 13px;
        color: var(--color-text-primary, #0f172a);
      }
    `,
  ],
})
export class ShortcutHelpDialogComponent {
  data = inject<{ shortcuts: ShortcutEntry[] }>(MAT_DIALOG_DATA);

  get categories(): string[] {
    const cats = new Set(this.data.shortcuts.map((s) => s.category));
    return Array.from(cats);
  }

  getByCategory(category: string): ShortcutEntry[] {
    return this.data.shortcuts.filter((s) => s.category === category);
  }

  parseKeys(keys: string): string[] {
    return keys.split(' → ').map((k) => k.trim());
  }
}
