import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, from } from 'rxjs';
import { map } from 'rxjs/operators';
import {
  ConfirmDialogMatComponent,
  ConfirmDialogData,
} from '@shared/components/confirm-dialog/confirm-dialog-mat.component';

/**
 * Service wrapper for styled confirmation dialogs.
 * Replaces all `window.confirm()` calls with a MatDialog-based modal.
 *
 * Usage:
 *   this.confirmDialog.open({ title: 'Obriši', message: '...', danger: true })
 *     .subscribe(confirmed => { if (confirmed) { ... } });
 */
@Injectable({ providedIn: 'root' })
export class ConfirmDialogService {
  private readonly dialog = inject(MatDialog);

  /**
   * Opens a confirmation dialog and returns Observable<boolean>.
   * Emits true if confirmed, false if cancelled or dismissed.
   */
  open(data: ConfirmDialogData): Observable<boolean> {
    const ref = this.dialog.open<ConfirmDialogMatComponent, ConfirmDialogData, boolean>(
      ConfirmDialogMatComponent,
      {
        data,
        width: '400px',
        maxWidth: '95vw',
        panelClass: 'rtz-confirm-dialog',
        autoFocus: 'dialog',
        restoreFocus: true,
      },
    );
    return from(ref.afterClosed()).pipe(map((result) => result === true));
  }
}