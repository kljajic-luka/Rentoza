import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  OwnerVerificationService,
  OwnerType,
  OwnerVerificationStatus,
} from '../../../../core/services/owner-verification.service';

/**
 * Owner verification component for PIB/JMBG submission.
 *
 * Features:
 * - Toggle between individual and legal entity
 * - JMBG/PIB format validation
 * - Submission status tracking
 */
@Component({
  selector: 'app-owner-verification',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, MatIconModule],
  templateUrl: './owner-verification.component.html',
  styleUrls: ['./owner-verification.component.scss'],
})
export class OwnerVerificationComponent implements OnInit {
  // State
  ownerType = signal<OwnerType>('INDIVIDUAL');
  status = signal<OwnerVerificationStatus | null>(null);
  loading = signal<boolean>(false);
  initializing = signal<boolean>(true); // Track initial load
  error = signal<string | null>(null);
  success = signal<boolean>(false);

  // Forms
  individualForm: FormGroup;
  legalEntityForm: FormGroup;

  constructor(private fb: FormBuilder, private verificationService: OwnerVerificationService) {
    // Individual form (JMBG)
    this.individualForm = this.fb.group({
      jmbg: ['', [Validators.required, Validators.pattern(/^\d{13}$/)]],
      bankAccountNumber: [''],
    });

    // Legal entity form (PIB)
    this.legalEntityForm = this.fb.group({
      pib: ['', [Validators.required, Validators.pattern(/^\d{9}$/)]],
      bankAccountNumber: ['', Validators.required],
    });
  }

  ngOnInit(): void {
    this.loadStatus();
  }

  loadStatus(): void {
    this.initializing.set(true);
    this.verificationService.getStatus().subscribe({
      next: (status) => {
        this.status.set(status);
        if (status.ownerType) {
          this.ownerType.set(status.ownerType);
        }
        this.initializing.set(false);
      },
      error: (err) => {
        console.error('Failed to load status:', err);
        this.initializing.set(false);
      },
    });
  }

  setOwnerType(type: OwnerType): void {
    this.ownerType.set(type);
    this.error.set(null);
  }

  submitIndividual(): void {
    if (this.individualForm.invalid) {
      this.error.set('JMBG mora imati tačno 13 cifara');
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.verificationService.submitIndividual(this.individualForm.value).subscribe({
      next: (status) => {
        this.loading.set(false);
        this.status.set(status);
        this.success.set(true);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err.error?.message || 'Neispravan JMBG format');
      },
    });
  }

  submitLegalEntity(): void {
    if (this.legalEntityForm.invalid) {
      this.error.set('PIB mora imati tačno 9 cifara');
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.verificationService.submitLegalEntity(this.legalEntityForm.value).subscribe({
      next: (status) => {
        this.loading.set(false);
        this.status.set(status);
        this.success.set(true);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err.error?.message || 'Neispravan PIB format');
      },
    });
  }

  getStatusLabel(): string {
    const currentStatus = this.status();
    if (!currentStatus) return '';
    switch (currentStatus.status) {
      case 'NOT_SUBMITTED':
        return 'Nije podnešeno';
      case 'PENDING_REVIEW':
        return 'Na čekanju';
      case 'VERIFIED':
        return 'Verifikovano ✓';
      default:
        return '';
    }
  }

  getStatusClass(): string {
    const currentStatus = this.status();
    if (!currentStatus) return '';
    switch (currentStatus.status) {
      case 'VERIFIED':
        return 'status-verified';
      case 'PENDING_REVIEW':
        return 'status-pending';
      default:
        return 'status-default';
    }
  }
}