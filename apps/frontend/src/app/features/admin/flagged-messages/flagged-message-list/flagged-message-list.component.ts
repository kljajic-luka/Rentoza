import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  AdminApiService,
  FlaggedMessageDto,
} from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-flagged-message-list',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatPaginatorModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  styleUrls: ['../../admin-shared.styles.scss'],
  template: `
    <div class="admin-page">
      <h1 class="page-title">Flagged Message Moderation Queue</h1>

      <div *ngIf="loading()" style="display: flex; justify-content: center; padding: 48px;">
        <mat-spinner diameter="48"></mat-spinner>
      </div>

      <div *ngIf="error()" class="error-banner" style="padding: 16px; margin-bottom: 16px; background: #fdecea; border-radius: 8px; color: #b71c1c;">
        {{ error() }}
      </div>

      <div *ngIf="!loading()" class="table-container">
        <div *ngIf="messages().length === 0 && !loading()" style="text-align: center; padding: 48px; color: rgba(0,0,0,.54);">
          <mat-icon style="font-size: 48px; width: 48px; height: 48px; margin-bottom: 12px;">check_circle</mat-icon>
          <p>No flagged messages to review.</p>
        </div>

        <table mat-table [dataSource]="messages()" *ngIf="messages().length > 0" style="width: 100%;">
          <ng-container matColumnDef="id">
            <th mat-header-cell *matHeaderCellDef>ID</th>
            <td mat-cell *matCellDef="let m">{{ m.id }}</td>
          </ng-container>

          <ng-container matColumnDef="sender">
            <th mat-header-cell *matHeaderCellDef>Sender</th>
            <td mat-cell *matCellDef="let m">User #{{ m.senderId }}</td>
          </ng-container>

          <ng-container matColumnDef="conversationId">
            <th mat-header-cell *matHeaderCellDef>Conversation</th>
            <td mat-cell *matCellDef="let m">#{{ m.conversationId }}</td>
          </ng-container>

          <ng-container matColumnDef="content">
            <th mat-header-cell *matHeaderCellDef>Message Content</th>
            <td mat-cell *matCellDef="let m" style="max-width: 300px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;"
                [matTooltip]="m.content">
              {{ m.content }}
            </td>
          </ng-container>

          <ng-container matColumnDef="flags">
            <th mat-header-cell *matHeaderCellDef>Flags</th>
            <td mat-cell *matCellDef="let m">
              <mat-chip color="warn" highlighted>{{ m.moderationFlags }}</mat-chip>
            </td>
          </ng-container>

          <ng-container matColumnDef="timestamp">
            <th mat-header-cell *matHeaderCellDef>Date</th>
            <td mat-cell *matCellDef="let m">{{ m.timestamp | date:'short' }}</td>
          </ng-container>

          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef>Actions</th>
            <td mat-cell *matCellDef="let m">
              <button mat-icon-button color="primary"
                      (click)="dismissFlags(m)"
                      matTooltip="Dismiss flags (mark OK)">
                <mat-icon>check</mat-icon>
              </button>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>

        <mat-paginator
          [length]="totalElements()"
          [pageSize]="pageSize"
          [pageIndex]="currentPage()"
          [pageSizeOptions]="[10, 20, 50]"
          (page)="onPage($event)"
          showFirstLastButtons>
        </mat-paginator>
      </div>
    </div>
  `,
})
export class FlaggedMessageListComponent implements OnInit {
  private adminApi = inject(AdminApiService);
  private notification = inject(AdminNotificationService);
  private destroyRef = inject(DestroyRef);

  displayedColumns = ['id', 'sender', 'conversationId', 'content', 'flags', 'timestamp', 'actions'];
  pageSize = 20;

  messages = signal<FlaggedMessageDto[]>([]);
  totalElements = signal(0);
  currentPage = signal(0);
  loading = signal(true);
  error = signal<string | null>(null);

  ngOnInit() {
    this.loadMessages();
  }

  loadMessages() {
    this.loading.set(true);
    this.error.set(null);
    this.adminApi.getFlaggedMessages(this.currentPage(), this.pageSize)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.messages.set(res.content);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(err.error?.message || 'Failed to load flagged messages');
          this.loading.set(false);
        },
      });
  }

  onPage(event: PageEvent) {
    this.currentPage.set(event.pageIndex);
    this.pageSize = event.pageSize;
    this.loadMessages();
  }

  dismissFlags(msg: FlaggedMessageDto) {
    this.adminApi.dismissMessageFlags(msg.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notification.showSuccess(`Flags dismissed for message #${msg.id}`);
          this.loadMessages();
        },
        error: (err) => {
          this.notification.showError(err.error?.message || 'Failed to dismiss flags');
        },
      });
  }
}
