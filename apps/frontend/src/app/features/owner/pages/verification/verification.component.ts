import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

enum VerificationStatus {
  NOT_SUBMITTED = 'NOT_SUBMITTED',
  PENDING = 'PENDING',
  APPROVED = 'APPROVED',
  REJECTED = 'REJECTED'
}

@Component({
  selector: 'app-verification',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './verification.component.html',
  styleUrls: ['./verification.component.scss']
})
export class VerificationComponent implements OnInit {
  protected readonly isLoading = signal(false);
  protected readonly status = signal<VerificationStatus>(VerificationStatus.NOT_SUBMITTED);
  protected readonly VerificationStatus = VerificationStatus;

  ngOnInit(): void {
    this.loadVerificationStatus();
  }

  private loadVerificationStatus(): void {
    this.isLoading.set(true);

    // TODO: Fetch from backend GET /api/owner/verification
    setTimeout(() => {
      this.status.set(VerificationStatus.NOT_SUBMITTED);
      this.isLoading.set(false);
    }, 500);
  }

  protected handleFileUpload(event: Event, documentType: string): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;

    const file = input.files[0];
    console.log(`Uploading ${documentType}:`, file.name);

    // TODO: Upload document to backend
  }

  protected getStatusClass(): string {
    switch (this.status()) {
      case VerificationStatus.PENDING:
        return 'status-pending';
      case VerificationStatus.APPROVED:
        return 'status-approved';
      case VerificationStatus.REJECTED:
        return 'status-rejected';
      default:
        return '';
    }
  }

  protected getStatusLabel(): string {
    switch (this.status()) {
      case VerificationStatus.NOT_SUBMITTED:
        return 'Nije podneto';
      case VerificationStatus.PENDING:
        return 'Na čekanju';
      case VerificationStatus.APPROVED:
        return 'Odobreno';
      case VerificationStatus.REJECTED:
        return 'Odbijeno';
      default:
        return '';
    }
  }
}
