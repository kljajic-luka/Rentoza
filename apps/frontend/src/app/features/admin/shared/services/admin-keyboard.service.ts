import { Injectable, inject, NgZone } from '@angular/core';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { ShortcutHelpDialogComponent } from '../dialogs/shortcut-help-dialog/shortcut-help-dialog.component';

export interface ShortcutEntry {
  keys: string;
  description: string;
  category: string;
}

@Injectable({ providedIn: 'root' })
export class AdminKeyboardService {
  private router = inject(Router);
  private dialog = inject(MatDialog);
  private ngZone = inject(NgZone);

  private pendingKey: string | null = null;
  private pendingTimeout: any = null;
  private active = false;

  readonly shortcuts: ShortcutEntry[] = [
    { keys: 'g → u', description: 'Go to Users', category: 'Navigation' },
    { keys: 'g → b', description: 'Go to Bookings', category: 'Navigation' },
    { keys: 'g → c', description: 'Go to Cars', category: 'Navigation' },
    { keys: 'g → f', description: 'Go to Financial', category: 'Navigation' },
    { keys: 'g → d', description: 'Go to Disputes', category: 'Navigation' },
    { keys: 'g → v', description: 'Go to Renter Verifications', category: 'Navigation' },
    { keys: 'g → h', description: 'Go to Dashboard (home)', category: 'Navigation' },
    { keys: '/', description: 'Focus search bar', category: 'Actions' },
    { keys: '?', description: 'Show keyboard shortcuts', category: 'Actions' },
  ];

  private readonly sequenceMap: Record<string, Record<string, () => void>> = {
    g: {
      u: () => this.router.navigate(['/admin/users']),
      b: () => this.router.navigate(['/admin/bookings']),
      c: () => this.router.navigate(['/admin/cars']),
      f: () => this.router.navigate(['/admin/financial']),
      d: () => this.router.navigate(['/admin/disputes']),
      v: () => this.router.navigate(['/admin/renter-verifications']),
      h: () => this.router.navigate(['/admin/dashboard']),
    },
  };

  enable(): void {
    this.active = true;
  }

  disable(): void {
    this.active = false;
    this.clearPending();
  }

  handleKeydown(event: KeyboardEvent): void {
    if (!this.active) return;

    // Ignore when typing in inputs, textareas, or content-editable
    const target = event.target as HTMLElement;
    const tagName = target.tagName.toLowerCase();
    if (
      tagName === 'input' ||
      tagName === 'textarea' ||
      tagName === 'select' ||
      target.isContentEditable
    ) {
      return;
    }

    // Ignore if modifier keys are held (except Shift for ?)
    if (event.ctrlKey || event.metaKey || event.altKey) return;

    const key = event.key;

    // Handle single-key shortcuts
    if (key === '/' && !this.pendingKey) {
      event.preventDefault();
      const searchInput = document.querySelector<HTMLInputElement>('.admin-search-input');
      if (searchInput) {
        searchInput.focus();
      } else {
        // Expand search first, then focus
        const searchBtn = document.querySelector<HTMLButtonElement>('.global-search button');
        if (searchBtn) {
          searchBtn.click();
          setTimeout(() => {
            document.querySelector<HTMLInputElement>('.admin-search-input')?.focus();
          }, 100);
        }
      }
      return;
    }

    if (key === '?' && !this.pendingKey) {
      event.preventDefault();
      this.showHelp();
      return;
    }

    // Handle two-key sequences
    if (this.pendingKey) {
      const seqActions = this.sequenceMap[this.pendingKey];
      if (seqActions && seqActions[key]) {
        event.preventDefault();
        this.ngZone.run(() => seqActions[key]());
      }
      this.clearPending();
      return;
    }

    // Start a new sequence
    if (this.sequenceMap[key]) {
      this.pendingKey = key;
      this.pendingTimeout = setTimeout(() => this.clearPending(), 800);
    }
  }

  showHelp(): void {
    this.ngZone.run(() => {
      this.dialog.open(ShortcutHelpDialogComponent, {
        width: '480px',
        maxHeight: '80vh',
        data: { shortcuts: this.shortcuts },
      });
    });
  }

  private clearPending(): void {
    this.pendingKey = null;
    if (this.pendingTimeout) {
      clearTimeout(this.pendingTimeout);
      this.pendingTimeout = null;
    }
  }
}
