import { Component, OnInit, inject } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AdminApiService, AdminCarDto } from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { CarActionDialogComponent } from '../dialogs/car-action-dialog/car-action-dialog.component';

@Component({
  selector: 'app-car-detail',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatDialogModule,
    MatProgressSpinnerModule,
  ],
  styleUrls: ['../../admin-shared.styles.scss', './car-detail.component.scss'],
  template: `
    <div class="admin-page">
      <button mat-button color="primary" class="back-btn" (click)="goBack()">
        <mat-icon>arrow_back</mat-icon>
        Back to Cars
      </button>

      <div *ngIf="loading" class="loading-block">
        <mat-spinner diameter="48"></mat-spinner>
      </div>

      <div *ngIf="!loading && car" class="car-grid">
        <div class="car-main">
          <mat-card class="surface-card surface-wide">
            <div
              class="hero"
              [style.backgroundImage]="
                'url(' + (car.imageUrl || 'assets/images/car-placeholder.png') + ')'
              "
            >
              <div class="hero-overlay" *ngIf="!car.imageUrl">
                <mat-icon>directions_car</mat-icon>
              </div>
            </div>

            <div class="car-header">
              <div>
                <h1 class="page-title">{{ car.brand }} {{ car.model }}</h1>
                <p class="muted">{{ car.year }}</p>
              </div>
              <div class="row wrap">
                <span class="badge" [ngClass]="car.available ? 'badge-success' : 'badge-warn'">
                  {{ car.available ? 'Available' : 'Unavailable' }}
                </span>
                <span *ngIf="!car.active" class="badge badge-neutral">Inactive</span>
              </div>
            </div>

            <div class="divider-line"></div>

            <div class="row wrap" style="gap: 12px;">
              <ng-container *ngIf="!car.available">
                <button mat-flat-button color="primary" (click)="openActionDialog('approve')">
                  <mat-icon>check</mat-icon>
                  Approve Listing
                </button>
                <button mat-stroked-button color="warn" (click)="openActionDialog('reject')">
                  <mat-icon>close</mat-icon>
                  Reject
                </button>
              </ng-container>
              <ng-container *ngIf="car.available">
                <button mat-flat-button color="warn" (click)="openActionDialog('suspend')">
                  <mat-icon>block</mat-icon>
                  Suspend Listing
                </button>
              </ng-container>
            </div>
          </mat-card>

          <mat-card class="surface-card surface-roomy">
            <mat-card-header>
              <mat-card-title>Vehicle Details</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <div class="spec-grid">
                <div>
                  <div class="mini-label">Transmission</div>
                  <div class="strong">Automatic</div>
                </div>
                <div>
                  <div class="mini-label">Fuel Type</div>
                  <div class="strong">Diesel</div>
                </div>
              </div>
            </mat-card-content>
          </mat-card>
        </div>

        <div class="car-side">
          <mat-card class="surface-card surface-roomy">
            <mat-card-header>
              <mat-icon mat-card-avatar>person</mat-icon>
              <mat-card-title>Owner</mat-card-title>
            </mat-card-header>
            <mat-card-content class="stack">
              <span class="strong">{{ car.ownerEmail }}</span>
              <button mat-button color="accent">View Profile</button>
            </mat-card-content>
          </mat-card>
        </div>
      </div>
    </div>
  `,
})
export class CarDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private location = inject(Location);
  private adminApi = inject(AdminApiService);
  private notification = inject(AdminNotificationService);
  private dialog = inject(MatDialog);

  carId: number | null = null;
  car: AdminCarDto | null = null;
  loading = true;

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.carId = +id;
      this.loadCar();
    } else {
      this.goBack();
    }
  }

  loadCar() {
    if (!this.carId) return;
    this.loading = true;
    this.adminApi.getCarDetail(this.carId).subscribe({
      next: (data) => {
        this.car = data;
        this.loading = false;
      },
      error: (err) => {
        console.error('Failed to load car', err);
        this.notification.showError('Car not found');
        this.loading = false;
      },
    });
  }

  goBack() {
    this.location.back();
  }

  openActionDialog(action: 'approve' | 'reject' | 'suspend' | 'reactivate') {
    if (!this.car) return;

    const dialogRef = this.dialog.open(CarActionDialogComponent, {
      width: '500px',
      data: {
        action,
        carBrand: this.car.brand,
        carModel: this.car.model,
      },
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result?.confirmed && this.carId) {
        this.performAction(action, result.reason);
      }
    });
  }

  performAction(action: string, reason?: string) {
    if (!this.carId) return;

    let obs;
    switch (action) {
      case 'approve':
        obs = this.adminApi.approveCar(this.carId);
        break;
      case 'reject':
        obs = this.adminApi.rejectCar(this.carId, reason || '');
        break;
      case 'suspend':
        obs = this.adminApi.suspendCar(this.carId);
        break;
      case 'reactivate':
        obs = this.adminApi.reactivateCar(this.carId);
        break;
      default:
        return;
    }

    if (obs) {
      obs.subscribe({
        next: () => {
          this.notification.showSuccess(`Car ${action}ed successfully`);
          this.loadCar();
        },
        error: () => this.notification.showError(`Failed to ${action} car`),
      });
    }
  }
}
