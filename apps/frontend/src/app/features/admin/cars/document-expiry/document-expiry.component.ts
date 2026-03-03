import { Component, OnInit, inject, signal, computed, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';

import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AdminApiService, ExpiringDocumentDto } from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';

type UrgencyLevel = 'critical' | 'warning' | 'ok';

@Component({
  selector: 'app-document-expiry',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatFormFieldModule,
    MatTooltipModule,
  ],
  templateUrl: './document-expiry.component.html',
  styleUrls: ['./document-expiry.component.scss'],
})
export class DocumentExpiryComponent implements OnInit {
  private adminApi = inject(AdminApiService);
  private notification = inject(AdminNotificationService);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);

  // State
  documents = signal<ExpiringDocumentDto[]>([]);
  loading = signal(false);
  daysFilter = signal(30);
  documentTypeFilter = signal<string | null>(null);
  urgencyFilter = signal<UrgencyLevel | null>(null);

  // Table columns
  readonly displayedColumns = ['car', 'owner', 'documentType', 'expiryDate', 'daysRemaining', 'actions'] as const;

  // Computed
  filteredDocuments = computed(() => {
    let docs = this.documents();
    const typeFilter = this.documentTypeFilter();
    const urgency = this.urgencyFilter();

    if (typeFilter) {
      docs = docs.filter((d) => d.documentType === typeFilter);
    }

    if (urgency) {
      docs = docs.filter((d) => this.getUrgency(d.daysRemaining) === urgency);
    }

    return docs;
  });

  documentTypes = computed(() => {
    const types = new Set(this.documents().map((d) => d.documentType));
    return Array.from(types).sort();
  });

  urgencyCounts = computed(() => {
    const docs = this.documents();
    return {
      critical: docs.filter((d) => d.daysRemaining < 7).length,
      warning: docs.filter((d) => d.daysRemaining >= 7 && d.daysRemaining <= 30).length,
      ok: docs.filter((d) => d.daysRemaining > 30).length,
    };
  });

  ngOnInit(): void {
    this.loadDocuments();
  }

  loadDocuments(): void {
    this.loading.set(true);
    this.adminApi.getExpiringDocuments(this.daysFilter()).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.loading.set(false)),
    ).subscribe({
      next: (docs) => {
        this.documents.set(docs);
      },
      error: () => {
        this.notification.showError('Failed to load expiring documents');
      },
    });
  }

  onDaysFilterChange(days: number): void {
    this.daysFilter.set(days);
    this.loadDocuments();
  }

  onDocumentTypeChange(type: string | null): void {
    this.documentTypeFilter.set(type);
  }

  onUrgencyChange(urgency: UrgencyLevel | null): void {
    this.urgencyFilter.set(this.urgencyFilter() === urgency ? null : urgency);
  }

  getUrgency(daysRemaining: number): UrgencyLevel {
    if (daysRemaining < 7) return 'critical';
    if (daysRemaining <= 30) return 'warning';
    return 'ok';
  }

  getUrgencyColor(daysRemaining: number): string {
    const urgency = this.getUrgency(daysRemaining);
    if (urgency === 'critical') return 'warn';
    if (urgency === 'warning') return 'accent';
    return 'primary';
  }

  getUrgencyLabel(daysRemaining: number): string {
    if (daysRemaining <= 0) return 'EXPIRED';
    if (daysRemaining < 7) return 'CRITICAL';
    if (daysRemaining <= 30) return 'EXPIRING SOON';
    return 'OK';
  }

  formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  }

  formatDocumentType(type: string): string {
    return type
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  viewCar(carId: number): void {
    this.router.navigate(['/admin/cars', carId, 'review']);
  }

  goBack(): void {
    this.router.navigate(['/admin/cars']);
  }
}
