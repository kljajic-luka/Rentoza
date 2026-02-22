import { Injectable } from '@angular/core';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { Subject } from 'rxjs';

/**
 * Serbian (sr-Latn) localization for Angular Material Paginator.
 *
 * WCAG 2.1 — 3.1.2 Language of Parts: UI controls must match page language.
 * This replaces the default English strings with Serbian equivalents.
 */
@Injectable()
export class SrMatPaginatorIntl implements MatPaginatorIntl {
  changes = new Subject<void>();

  firstPageLabel = 'Prva strana';
  lastPageLabel = 'Poslednja strana';
  nextPageLabel = 'Sledeća strana';
  previousPageLabel = 'Prethodna strana';
  itemsPerPageLabel = 'Po stranici:';

  getRangeLabel(page: number, pageSize: number, length: number): string {
    if (length === 0) {
      return 'Nema rezultata';
    }
    const amountPages = Math.ceil(length / pageSize);
    const from = page * pageSize + 1;
    const to = Math.min((page + 1) * pageSize, length);
    return `${from} – ${to} od ${length}`;
  }
}
