/**
 * Guest Check-In Component
 *
 * Handles the guest's portion of check-in:
 * 1. Review host's photos of vehicle condition
 * 2. [Phase 2] Take comparison photos (dual-party)
 * 3. Acknowledge condition is acceptable OR mark damage hotspots
 * 4. Optionally reveal lockbox code if remote handoff
 */
import {
  Component,
  Input,
  Output,
  EventEmitter,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
  OnInit,
  OnDestroy,
  OnChanges,
  HostListener,
  effect,
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Subject, debounceTime, firstValueFrom } from 'rxjs';

import { CheckInService } from '../../../core/services/check-in.service';
import { GeolocationService } from '../../../core/services/geolocation.service';
import { PhotoGuidanceService } from '../../../core/services/photo-guidance.service';
import {
  CheckInPersistenceService,
  CaptureState,
} from '../../../core/services/check-in-persistence.service';
import {
  CheckInStatusDTO,
  CheckInPhotoDTO,
  CheckInPhotoType,
  ExifValidationStatus,
} from '../../../core/models/check-in.model';
import { GuestCheckInPhotoSubmissionDTO } from '../../../core/models/photo-guidance.model';
import { GuidedPhotoCaptureComponent } from './guided-photo-capture/guided-photo-capture.component';
import { PhotoComparisonComponent } from './photo-comparison/photo-comparison.component';
import { PhotoViewerDialogComponent } from '../../../shared/components/photo-viewer-dialog/photo-viewer-dialog.component';
import {
  CheckInRecoveryDialogComponent,
  RecoveryDialogData,
  RecoveryDialogResult,
} from './check-in-recovery-dialog/check-in-recovery-dialog.component';
import { environment } from '../../../../environments/environment';
import { ReadOnlyPickupLocationComponent } from '../components/readonly-pickup-location/readonly-pickup-location.component';
import { PickupLocationData } from '../../../core/models/booking-details.model';

type GuestPhotoWorkflowState =
  | 'not_started'
  | 'local_draft_resumable'
  | 'uploading'
  | 'uploaded_confirmed'
  | 'upload_failed_retryable';

@Component({
  selector: 'app-guest-check-in',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatDialogModule,
    MatInputModule,
    MatFormFieldModule,
    MatCheckboxModule,
    MatChipsModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    GuidedPhotoCaptureComponent,
    PhotoComparisonComponent,
    ReadOnlyPickupLocationComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="guest-check-in">
      <!-- Vehicle info header -->
      @if (status?.car) {
        <mat-card class="vehicle-card">
          @if (status?.car?.imageUrl) {
            <img [src]="status!.car.imageUrl" [alt]="vehicleTitle()" class="vehicle-image" />
          }
          <mat-card-content>
            <h3>{{ vehicleTitle() }}</h3>
            <div class="vehicle-details">
              <span><mat-icon>speed</mat-icon> {{ status?.odometerReading | number }} km</span>
              <span><mat-icon>local_gas_station</mat-icon> {{ status?.fuelLevelPercent }}%</span>
            </div>
          </mat-card-content>
        </mat-card>
      }

      <!-- Pickup Location Section (shows where to meet the host) -->
      @if (pickupLocationData()) {
        <div class="pickup-location-section">
          <div class="section-header small"></div>
          <app-readonly-pickup-location
            [pickupLocation]="pickupLocationData()!"
            [mode]="'standard'"
          />
          <p class="gps-helper-text">
            <mat-icon>info</mat-icon>
            <span
              >GPS lokacija vozila se automatski određuje iz fotografija radi revizijskog
              traga</span
            >
          </p>
        </div>
      }

      <!-- Warning banner: Host photos incomplete or have rejections (only shown in comparison) -->
      @if (hasPhotosWithIssues() && guestPhotosComplete()) {
        <div class="photo-issues-warning">
          <mat-icon>warning</mat-icon>
          <div class="warning-content">
            <span class="warning-title">Nepotpune fotografije domaćina</span>
            <span class="warning-message">
              Neke fotografije od domaćina imaju probleme sa validacijom. Pregledajte pažljivo u
              poređenju ispod.
            </span>
          </div>
        </div>
      }

      <!-- ═══════════════════════════════════════════════════════════════════════════
           PHASE 2: DUAL-PARTY PHOTO CAPTURE
           Guest takes their own comparison photos for dispute resolution
           ═══════════════════════════════════════════════════════════════════════════ -->
      @if (serverGuestPhotoVerificationEnabled() && !guestPhotosComplete()) {
        <div class="dual-party-section">
          <div class="section-header">
            <mat-icon>add_a_photo</mat-icon>
            <div>
              <h2>Vaše fotografije vozila</h2>
              <p>Uslikajte vozilo za dodatnu dokumentaciju</p>
            </div>
            @if (!serverGuestPhotosRequired()) {
              <mat-chip class="optional-badge">Opciono</mat-chip>
            }
          </div>

          @if (guestPhotoWorkflowState() === 'uploading') {
            <mat-card class="capture-prompt-card uploading-state-card">
              <mat-card-content>
                <div class="capture-prompt">
                  <mat-spinner diameter="40"></mat-spinner>
                  <div class="prompt-text">
                    <h4>Otpremanje je u toku</h4>
                    <p>{{ guestPhotoStatusMessage() }}</p>
                  </div>
                </div>
              </mat-card-content>
            </mat-card>
          } @else if (!showGuidedCapture()) {
            <!-- Start capture button -->
            <mat-card class="capture-prompt-card">
              <mat-card-content>
                <div class="capture-prompt">
                  <mat-icon>camera_enhance</mat-icon>
                  <div class="prompt-text">
                    <h4>Dokumentujte stanje vozila</h4>
                    <p>
                      Snimite fotografije vozila iz svih uglova. Ove fotografije služe kao dokaz u
                      slučaju neslaganja.
                    </p>
                  </div>
                </div>
                <div class="capture-actions">
                  <button
                    mat-raised-button
                    color="primary"
                    (click)="continueGuestPhotoFlow()"
                    class="start-capture-btn"
                  >
                    <mat-icon>photo_camera</mat-icon>
                    {{ guestPhotoPrimaryActionLabel() }}
                  </button>
                  @if (guestPhotoWorkflowState() === 'upload_failed_retryable') {
                    <button mat-stroked-button (click)="startGuidedCapture()" class="skip-btn">
                      Ponovo snimi
                    </button>
                  } @else if (!serverGuestPhotosRequired()) {
                    <button mat-stroked-button (click)="skipGuestPhotos()" class="skip-btn">
                      Preskoči
                    </button>
                  }
                </div>
                @if (guestPhotoStatusMessage()) {
                  <p class="upload-status-hint">{{ guestPhotoStatusMessage() }}</p>
                }
                @if (guestPhotosProgress() > 0) {
                  <div class="progress-indicator">
                    <mat-icon>check_circle</mat-icon>
                    <span>{{ guestPhotosProgress() }}% završeno</span>
                  </div>
                }
              </mat-card-content>
            </mat-card>
          } @else {
            <!-- Guided photo capture component -->
            <app-guided-photo-capture
              [bookingId]="bookingId"
              [mode]="'guest-checkin'"
              [restoredState]="restoredCaptureState()"
              [preCompletedPhotoTypes]="preCompletedGuestPhotoTypes()"
              (captureComplete)="onGuestPhotosComplete($event)"
              (captureCancelled)="onGuestPhotosCancelled()"
            />
          }
        </div>
      }

      <!-- Photo comparison (PRIVACY-FIRST: opt-in after guest photos complete) -->
      @if (serverGuestPhotoVerificationEnabled() && guestPhotosComplete() && hasPhotosToCompare()) {
        <div class="comparison-section">
          @if (!showPhotoComparison()) {
            <!-- Collapsed state - show toggle button -->
            <mat-card class="comparison-toggle-card">
              <mat-card-content>
                <div class="comparison-toggle-content">
                  <mat-icon class="toggle-icon">compare</mat-icon>
                  <div class="toggle-text">
                    <h4>Upoređivanje fotografija</h4>
                    <p>Pregledajte razlike između vaših i domaćinovih fotografija (opciono)</p>
                  </div>
                </div>
                <button
                  mat-stroked-button
                  color="primary"
                  (click)="togglePhotoComparison()"
                  class="toggle-btn"
                >
                  <mat-icon>visibility</mat-icon>
                  Prikaži poređenje
                </button>
              </mat-card-content>
            </mat-card>
          } @else {
            <!-- Expanded state - show full comparison -->
            <div class="section-header">
              <mat-icon>compare</mat-icon>
              <div>
                <h2>Upoređivanje fotografija</h2>
                <p>Pregledajte razlike između fotografija domaćina i vaših</p>
              </div>
              <button
                mat-icon-button
                (click)="togglePhotoComparison()"
                class="collapse-btn"
                matTooltip="Sakrij poređenje"
              >
                <mat-icon>expand_less</mat-icon>
              </button>
            </div>

            <app-photo-comparison
              [hostPhotos]="status?.vehiclePhotos ?? []"
              [guestPhotos]="guestCapturedPhotos()"
              [sessionId]="status?.checkInSessionId"
              (continue)="onComparisonContinue()"
              (reportDiscrepancy)="onReportDiscrepancy()"
            />
          }
        </div>
      }

      <!-- Condition acknowledgment -->
      <mat-card class="condition-card">
        <mat-card-content>
          <h3>Potvrdite stanje vozila</h3>

          <form [formGroup]="conditionForm">
            <!-- Accept condition checkbox -->
            <mat-checkbox
              formControlName="conditionAccepted"
              color="primary"
              class="accept-checkbox"
            >
              Pregledao/la sam fotografije i prihvatam trenutno stanje vozila
            </mat-checkbox>

            <!-- Report damage option (simplified - photos serve as documentation) -->
            @if (!conditionForm.get('conditionAccepted')?.value) {
              <div class="damage-section">
                <div class="damage-info">
                  <mat-icon>info_outline</mat-icon>
                  <p>
                    Ukoliko primetite bilo kakvo oštećenje, vaše fotografije služe kao
                    dokumentacija. Dodajte komentar ispod za dodatne informacije.
                  </p>
                </div>

                <!-- Comment for damage -->
                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Opis uočenih problema (opciono)</mat-label>
                  <textarea
                    matInput
                    formControlName="conditionComment"
                    rows="3"
                    placeholder="Opišite uočeno oštećenje ili problem..."
                  ></textarea>
                  <mat-hint
                    >Vaše fotografije su glavni dokaz - komentar je dodatna informacija</mat-hint
                  >
                </mat-form-field>
              </div>
            }
          </form>
        </mat-card-content>
      </mat-card>

      <!-- Lockbox section (if available) -->
      @if (status?.lockboxAvailable && !lockboxCode()) {
        <mat-card class="lockbox-card">
          <mat-card-content>
            <div class="lockbox-header">
              <mat-icon>lock</mat-icon>
              <div>
                <h4>Lockbox pristup</h4>
                <p>Vozilo je dostupno sa lockbox-om</p>
              </div>
            </div>
            <button
              mat-stroked-button
              color="primary"
              [disabled]="!status?.geofenceValid || checkInService.isLoading()"
              (click)="revealLockboxCode()"
            >
              @if (!status?.geofenceValid) {
                <ng-container>
                  <mat-icon>location_off</mat-icon>
                  Pristupite bliže vozilu
                </ng-container>
              } @else {
                <ng-container>
                  <mat-icon>visibility</mat-icon>
                  Prikaži kod
                </ng-container>
              }
            </button>
            @if (!status?.geofenceValid && status?.geofenceDistanceMeters) {
              <p class="distance-info">
                Udaljenost: {{ status?.geofenceDistanceMeters | number: '1.0-0' }}m
              </p>
            }
          </mat-card-content>
        </mat-card>
      }

      <!-- Revealed lockbox code -->
      @if (lockboxCode()) {
        <mat-card class="lockbox-revealed">
          <mat-card-content>
            <mat-icon>lock_open</mat-icon>
            <div class="code">{{ lockboxCode() }}</div>
            <p>Unesite ovaj kod u lockbox</p>
          </mat-card-content>
        </mat-card>
      }

      <!-- Submit button -->
      <div class="submit-section">
        <button
          mat-raised-button
          color="primary"
          [disabled]="!canSubmit()"
          (click)="submitAcknowledgment()"
          class="submit-button"
        >
          @if (checkInService.isLoading()) {
            <mat-spinner diameter="24"></mat-spinner>
          } @else if (
            !conditionForm.get('conditionAccepted')?.value &&
            conditionForm.get('conditionComment')?.value?.trim()
          ) {
            <ng-container>
              <mat-icon>report</mat-icon>
              Prijavi problem
            </ng-container>
          } @else {
            <ng-container>
              <mat-icon>check</mat-icon>
              Potvrdi stanje vozila
            </ng-container>
          }
        </button>

        @if (!canSubmit() && locationRequired && !geolocationService.hasPosition()) {
          <p class="submit-hint">
            <mat-icon>location_off</mat-icon>
            Potreban je pristup lokaciji za potvrdu
          </p>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .guest-check-in {
        padding: 16px;
      }

      /* Vehicle card */
      .vehicle-card {
        margin-bottom: 20px;
        background: var(--color-surface, #ffffff);
      }

      .vehicle-image {
        width: 100%;
        height: 160px;
        object-fit: cover;
      }

      .vehicle-card h3 {
        margin: 12px 0 8px;
        color: var(--color-text-primary, #212121);
      }

      .vehicle-details {
        display: flex;
        gap: 16px;
        color: var(--color-text-muted, #757575);
      }

      .vehicle-details span {
        display: flex;
        align-items: center;
        gap: 4px;
      }

      .vehicle-details mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      /* Pickup Location Section */
      .pickup-location-section {
        margin-bottom: 24px;
        padding: 16px;
        background: var(--color-surface-muted, #f5f5f5);
        border-radius: 12px;
        border: 1px solid var(--color-border-subtle, #e0e0e0);
      }

      .pickup-location-section .section-header.small {
        margin-bottom: 12px;
      }

      .pickup-location-section .section-header.small h3 {
        margin: 0;
        font-size: 16px;
      }

      .pickup-location-section .section-header.small p {
        margin: 2px 0 0;
        font-size: 13px;
      }

      /* Section header */
      .section-header {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 16px;
      }

      .section-header mat-icon {
        font-size: 28px;
        width: 28px;
        height: 28px;
        color: var(--brand-primary);
      }

      .section-header h2 {
        margin: 0;
        font-size: 18px;
        color: var(--color-text-primary, #212121);
      }

      .section-header p {
        margin: 4px 0 0;
        font-size: 14px;
        color: var(--color-text-muted, #757575);
      }

      /* Photo gallery */
      .photo-gallery {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 8px;
        margin-bottom: 20px;
      }

      .photo-item {
        position: relative;
        aspect-ratio: 4/3;
        border-radius: 8px;
        overflow: hidden;
        cursor: pointer;
        border: 1px solid var(--color-border-subtle, #e0e0e0);
      }

      .photo-item img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        /* CRITICAL: Respect EXIF orientation metadata for rotated photos */
        image-orientation: from-image;
      }

      .photo-label {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        padding: 4px 8px;
        background: rgba(0, 0, 0, 0.6);
        color: white;
        font-size: 11px;
        text-align: center;
      }

      .validation-badge {
        position: absolute;
        top: 4px;
        right: 4px;
        padding: 4px;
        border-radius: 50%;
        background: rgba(0, 0, 0, 0.5);
      }

      .validation-badge.warning {
        background: var(--warn-bg, #fff3e0);
        color: var(--warn-color, #ff9800);
      }

      .validation-badge.invalid {
        background: var(--error-bg, #ffebee);
        color: var(--error-color, #f44336);
      }

      .validation-badge mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      /* Condition card */
      .condition-card {
        margin-bottom: 20px;
        background: var(--color-surface, #ffffff);
      }

      .condition-card h3 {
        margin: 0 0 16px;
        color: var(--color-text-primary, #212121);
      }

      .accept-checkbox {
        display: flex;
        margin-bottom: 16px;
      }

      .damage-section {
        padding: 16px;
        background: var(--color-surface-muted, #f5f5f5);
        border-radius: 8px;
      }

      .damage-info {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        margin-bottom: 16px;
        padding: 12px;
        background: var(--warning-surface, #fff3e0);
        border-radius: 8px;
        border-left: 4px solid var(--warning-color, #ff9800);
      }

      :host-context(.dark-theme) .damage-info,
      :host-context(.theme-dark) .damage-info {
        background: rgba(255, 152, 0, 0.12);
        border-left-color: rgba(251, 191, 36, 0.7);
      }

      .damage-info mat-icon {
        color: var(--warning-color, #ff9800);
        flex-shrink: 0;
        margin-top: 2px;
      }

      :host-context(.dark-theme) .damage-info mat-icon,
      :host-context(.theme-dark) .damage-info mat-icon {
        color: #fbbf24;
      }

      .damage-info p {
        margin: 0;
        font-size: 14px;
        color: var(--color-text-primary, #212121);
        line-height: 1.5;
      }

      :host-context(.dark-theme) .damage-info p,
      :host-context(.theme-dark) .damage-info p {
        color: rgba(226, 232, 240, 0.9);
      }

      .full-width {
        width: 100%;
      }

      /* Lockbox */
      .lockbox-card {
        margin-bottom: 20px;
        background: var(--color-surface, #ffffff);
      }

      .lockbox-header {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 12px;
      }

      .lockbox-header mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        color: var(--brand-primary);
      }

      .lockbox-header h4 {
        margin: 0;
        color: var(--color-text-primary, #212121);
      }

      .lockbox-header p {
        margin: 4px 0 0;
        font-size: 13px;
        color: var(--color-text-muted, #757575);
      }

      .distance-info {
        margin: 8px 0 0;
        font-size: 12px;
        color: var(--color-text-muted, #757575);
      }

      .lockbox-revealed {
        margin-bottom: 20px;
        text-align: center;
        background: var(--success-bg, #e8f5e9);
      }

      .lockbox-revealed mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: var(--success-color, #4caf50);
        margin-bottom: 8px;
      }

      .lockbox-revealed .code {
        font-size: 36px;
        font-weight: bold;
        font-family: monospace;
        letter-spacing: 4px;
        margin: 8px 0;
        color: var(--color-text-primary, #212121);
      }

      .lockbox-revealed p {
        margin: 0;
        color: var(--color-text-muted, #757575);
      }

      /* Submit */
      .submit-section {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 12px;
        padding: 16px 0;
      }

      .submit-button {
        width: 100%;
        height: 48px;
        font-size: 16px;
      }

      .submit-hint {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 13px;
        color: var(--warn-color, #ff9800);
        margin: 0;
      }

      /* Photo Issues Warning Banner */
      .photo-issues-warning {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        padding: 12px 16px;
        margin: 0 0 16px;
        background: linear-gradient(135deg, #fff3e0 0%, #ffe0b2 100%);
        border: 1px solid #ffb74d;
        border-radius: 8px;
        border-left: 4px solid #ff9800;
      }

      .photo-issues-warning mat-icon {
        color: #f57c00;
        flex-shrink: 0;
        margin-top: 2px;
      }

      .photo-issues-warning .warning-content {
        flex: 1;
      }

      .photo-issues-warning .warning-content strong {
        display: block;
        color: #e65100;
        margin-bottom: 4px;
        font-size: 14px;
      }

      .photo-issues-warning .warning-content p {
        margin: 0;
        font-size: 13px;
        color: #bf360c;
        line-height: 1.4;
      }

      /* ═══════════════════════════════════════════════════════════════════════════
         DUAL-PARTY PHOTO CAPTURE STYLES
         ═══════════════════════════════════════════════════════════════════════════ */
      .dual-party-section {
        margin-bottom: 24px;
        padding: 16px;
        background: linear-gradient(135deg, #e3f2fd 0%, #f3e5f5 100%);
        border-radius: 12px;
        border: 1px solid #90caf9;
      }

      :host-context(.dark-theme) .dual-party-section,
      :host-context(.theme-dark) .dual-party-section {
        background: linear-gradient(
          135deg,
          rgba(33, 150, 243, 0.15) 0%,
          rgba(156, 39, 176, 0.12) 100%
        );
        border-color: rgba(144, 202, 249, 0.25);
      }

      .dual-party-section .section-header {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        margin-bottom: 16px;
      }

      .dual-party-section .section-header mat-icon {
        font-size: 28px;
        width: 28px;
        height: 28px;
        color: var(--brand-primary);
      }

      :host-context(.dark-theme) .dual-party-section .section-header mat-icon,
      :host-context(.theme-dark) .dual-party-section .section-header mat-icon {
        color: #b388ff;
      }

      .dual-party-section .section-header h2,
      .dual-party-section .section-header p {
        color: var(--color-text-primary, #212121);
      }

      :host-context(.dark-theme) .dual-party-section .section-header h2,
      :host-context(.theme-dark) .dual-party-section .section-header h2 {
        color: rgba(226, 232, 240, 0.95);
      }

      .dual-party-section .section-header p {
        color: var(--color-text-muted, #757575);
      }

      :host-context(.dark-theme) .dual-party-section .section-header p,
      :host-context(.theme-dark) .dual-party-section .section-header p {
        color: rgba(148, 163, 184, 0.85);
      }

      .optional-badge {
        margin-left: auto;
        font-size: 11px !important;
        height: 24px !important;
        min-height: 24px !important;
        background: rgba(124, 77, 255, 0.1) !important;
        color: #7c4dff !important;
      }

      :host-context(.dark-theme) .optional-badge,
      :host-context(.theme-dark) .optional-badge {
        background: rgba(124, 77, 255, 0.2) !important;
        color: #b388ff !important;
      }

      .capture-prompt-card {
        background: var(--color-surface, #ffffff);
        border-radius: 8px;
      }

      :host-context(.dark-theme) .capture-prompt-card,
      :host-context(.theme-dark) .capture-prompt-card {
        background: rgba(30, 41, 59, 0.8);
        border: 1px solid rgba(94, 117, 168, 0.25);
      }

      .capture-prompt {
        display: flex;
        align-items: flex-start;
        gap: 16px;
        margin-bottom: 16px;
      }

      .capture-prompt mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: var(--brand-primary);
        opacity: 0.9;
      }

      :host-context(.dark-theme) .capture-prompt mat-icon,
      :host-context(.theme-dark) .capture-prompt mat-icon {
        color: #b388ff;
        opacity: 1;
      }

      .capture-prompt .prompt-text h4 {
        margin: 0 0 4px;
        color: var(--color-text-primary, #212121);
      }

      :host-context(.dark-theme) .capture-prompt .prompt-text h4,
      :host-context(.theme-dark) .capture-prompt .prompt-text h4 {
        color: rgba(226, 232, 240, 0.95);
      }

      .capture-prompt .prompt-text p {
        margin: 0;
        font-size: 13px;
        color: var(--color-text-muted, #757575);
        line-height: 1.4;
      }

      :host-context(.dark-theme) .capture-prompt .prompt-text p,
      :host-context(.theme-dark) .capture-prompt .prompt-text p {
        color: rgba(148, 163, 184, 0.85);
      }

      .capture-actions {
        display: flex;
        gap: 12px;
        margin-bottom: 12px;
      }

      .start-capture-btn {
        flex: 1;
      }

      .skip-btn {
        min-width: 100px;
      }

      .progress-indicator {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 13px;
        color: var(--success-color, #4caf50);
      }

      .upload-status-hint {
        margin: 0 0 12px;
        font-size: 13px;
        color: var(--color-text-muted, #757575);
      }

      .progress-indicator mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      /* Comparison Section */
      .comparison-section {
        margin-bottom: 24px;
      }

      .comparison-section .section-header {
        margin-bottom: 16px;
      }

      .comparison-section .section-header mat-icon {
        color: #ff9800;
      }

      /* Privacy-First: Comparison Toggle Card */
      .comparison-toggle-card {
        background: var(--color-surface, #ffffff);
        border: 1px dashed var(--color-border-subtle, #e0e0e0);
        border-radius: 12px;
      }

      :host-context(.dark-theme) .comparison-toggle-card,
      :host-context(.theme-dark) .comparison-toggle-card {
        background: rgba(30, 41, 59, 0.6);
        border-color: rgba(94, 117, 168, 0.3);
      }

      .comparison-toggle-content {
        display: flex;
        align-items: center;
        gap: 16px;
        margin-bottom: 16px;
      }

      .comparison-toggle-content .toggle-icon {
        font-size: 36px;
        width: 36px;
        height: 36px;
        color: #ff9800;
        opacity: 0.8;
      }

      .comparison-toggle-content .toggle-text h4 {
        margin: 0 0 4px;
        font-size: 16px;
        color: var(--color-text-primary, #212121);
      }

      .comparison-toggle-content .toggle-text p {
        margin: 0;
        font-size: 13px;
        color: var(--color-text-muted, #757575);
      }

      :host-context(.dark-theme) .comparison-toggle-content .toggle-text h4,
      :host-context(.theme-dark) .comparison-toggle-content .toggle-text h4 {
        color: rgba(226, 232, 240, 0.95);
      }

      :host-context(.dark-theme) .comparison-toggle-content .toggle-text p,
      :host-context(.theme-dark) .comparison-toggle-content .toggle-text p {
        color: rgba(148, 163, 184, 0.85);
      }

      .toggle-btn {
        width: 100%;
      }

      .comparison-section .section-header {
        position: relative;
      }

      .collapse-btn {
        position: absolute;
        right: 0;
        top: 50%;
        transform: translateY(-50%);
      }
    `,
  ],
})
export class GuestCheckInComponent implements OnInit, OnDestroy, OnChanges {
  @Input() bookingId!: number;
  @Input() status!: CheckInStatusDTO | null;
  @Output() completed = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);
  checkInService = inject(CheckInService);
  geolocationService = inject(GeolocationService);
  private photoGuidanceService = inject(PhotoGuidanceService);
  private persistenceService = inject(CheckInPersistenceService);

  // ═══════════════════════════════════════════════════════════════════════════
  // STATE SIGNALS
  // ═══════════════════════════════════════════════════════════════════════════
  private _lockboxCode = signal<string | null>(null);
  private _conditionAccepted = signal<boolean>(false);
  private _status = signal<CheckInStatusDTO | null>(null);

  // Dual-party photo capture state
  private _showGuidedCapture = signal(false);
  private _guestPhotosSkipped = signal(false);
  private _guestCapturedPhotos = signal<CheckInPhotoDTO[]>([]);
  private _guestPhotoWorkflowState = signal<GuestPhotoWorkflowState>('not_started');
  private _pendingGuestSubmission = signal<GuestCheckInPhotoSubmissionDTO | null>(null);
  private _preCompletedGuestPhotoTypes = signal<CheckInPhotoType[]>([]);

  // Persistence state
  private _restoredCaptureState = signal<CaptureState | undefined>(undefined);
  private _hasUnsavedPhotos = signal(false);

  // PRIVACY-FIRST: Photo comparison is opt-in, not automatic
  private _showPhotoComparison = signal(false);

  // Public readonly signals
  lockboxCode = this._lockboxCode.asReadonly();
  conditionAccepted = this._conditionAccepted.asReadonly();
  showGuidedCapture = this._showGuidedCapture.asReadonly();
  showPhotoComparison = this._showPhotoComparison.asReadonly();
  guestPhotoWorkflowState = this._guestPhotoWorkflowState.asReadonly();
  guestPhotosComplete = computed(
    () =>
      this._guestPhotoWorkflowState() === 'uploaded_confirmed' ||
      this._guestPhotosSkipped(),
  );
  guestCapturedPhotos = this._guestCapturedPhotos.asReadonly();
  restoredCaptureState = this._restoredCaptureState.asReadonly();
  preCompletedGuestPhotoTypes = this._preCompletedGuestPhotoTypes.asReadonly();
  serverGuestPhotoVerificationEnabled = computed(
    () => this._status()?.guestPhotoVerificationEnabled === true,
  );
  serverGuestPhotosRequired = computed(() => this._status()?.guestPhotosRequired === true);
  serverGuestPhotoCount = computed(() => this._status()?.guestConfirmedPhotoCount ?? 0);
  serverGuestPhotoMissingTypes = computed(() => this._status()?.missingGuestPhotoTypes ?? []);
  guestPhotoPrimaryActionLabel = computed(() => {
    const state = this._guestPhotoWorkflowState();
    if (state === 'upload_failed_retryable') return 'Pokušaj ponovo';
    if (this.hasDraftReadyForUpload()) return 'Pošalji sačuvane fotografije';
    if (state === 'local_draft_resumable') return 'Nastavi snimanje';
    return 'Započni snimanje';
  });
  guestPhotoStatusMessage = computed(() => {
    const state = this._guestPhotoWorkflowState();
    if (state === 'uploading') {
      return 'Fotografije se otpremaju. Sačekajte potvrdu servera.';
    }
    if (state === 'upload_failed_retryable') {
      return 'Otpremanje nije potvrđeno. Sačuvani nacrt ostaje dostupan za ponovni pokušaj.';
    }
    if (state === 'local_draft_resumable' && this.hasDraftReadyForUpload()) {
      return 'Sačuvane fotografije čekaju potvrdu servera. Možete odmah nastaviti slanje.';
    }
    return null;
  });

  /** Progress percentage for guest photo capture */
  guestPhotosProgress = computed(() => {
    if (this.serverGuestPhotoVerificationEnabled()) {
      return Math.round((this.serverGuestPhotoCount() / 8) * 100);
    }
    return this.photoGuidanceService.progress();
  });

  /** Whether there are photos to compare (host photos exist) */
  hasPhotosToCompare = computed(() => {
    const hostPhotos = this._status()?.vehiclePhotos ?? [];
    const guestPhotos = this._guestCapturedPhotos();
    return hostPhotos.length > 0 && guestPhotos.length > 0;
  });

  // Form
  conditionForm: FormGroup = this.fb.group({
    conditionAccepted: [false],
    conditionComment: [''],
  });

  // Form change debounce for persistence
  private formChangeSubject = new Subject<void>();

  // Auto-save form state effect
  private readonly formSaveEffect = effect(() => {
    // Track form values (trigger on changes)
    const comment = this.conditionForm.get('conditionComment')?.value;
    const accepted = this._conditionAccepted();

    // Debounced save happens in formChangeSubject subscription
  });

  constructor() {
    // Sync form checkbox with signal for reactive computed
    this.conditionForm.get('conditionAccepted')?.valueChanges.subscribe((value) => {
      this._conditionAccepted.set(value);
      this.formChangeSubject.next();
    });

    // Debounce form changes and persist
    this.conditionForm.get('conditionComment')?.valueChanges.subscribe(() => {
      this.formChangeSubject.next();
    });

    this.formChangeSubject.pipe(debounceTime(500)).subscribe(() => {
      this.persistFormState();
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // LIFECYCLE HOOKS
  // ═══════════════════════════════════════════════════════════════════════════

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['status']) {
      this._status.set(this.status ?? null);
      void this.reconcileGuestPhotoStateFromServer();
    }
  }

  async ngOnInit(): Promise<void> {
    this._status.set(this.status ?? null);

    // CRITICAL: Wait for persistence service to be ready before any DB operations
    await this.persistenceService.waitForReady();

    await this.reconcileGuestPhotoStateFromServer();

    // Check for saved session and show recovery dialog if found
    if (!this.guestPhotosComplete()) {
      await this.checkForSavedSession();
    }

    // Acquire lock for this booking
    const lockAcquired = await this.persistenceService.acquireLock(this.bookingId);
    if (!lockAcquired) {
      this.snackBar.open('Sesija je aktivna u drugom tabu', 'OK', { duration: 5000 });
    }

    // Restore form state if available
    await this.restoreFormState();
  }

  ngOnDestroy(): void {
    // Release the lock when leaving
    this.persistenceService.releaseLock(this.bookingId);
    this.formChangeSubject.complete();
  }

  /**
   * Warn user before leaving page if they have unsaved photos.
   */
  @HostListener('window:beforeunload', ['$event'])
  onBeforeUnload(event: BeforeUnloadEvent): void {
    const hasPhotos =
      (this._guestCapturedPhotos().length > 0 || this._pendingGuestSubmission() !== null) &&
      !this.guestPhotosComplete();

    if (hasPhotos || this._showGuidedCapture()) {
      event.preventDefault();
      event.returnValue =
        'Imate nesačuvane fotografije. Da li ste sigurni da želite da napustite stranicu?';
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PERSISTENCE METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Check for a saved session and show recovery dialog if found.
   */
  private async checkForSavedSession(): Promise<void> {
    if (this.guestPhotosComplete()) {
      return;
    }

    const sessionInfo = await this.persistenceService.checkForSavedSession(
      this.bookingId,
      'guest-checkin',
    );

    if (sessionInfo.exists) {
      this._guestPhotoWorkflowState.set('local_draft_resumable');

      const dialogRef = this.dialog.open(CheckInRecoveryDialogComponent, {
        data: {
          sessionInfo,
          bookingId: this.bookingId,
          mode: 'guest-checkin',
        } as RecoveryDialogData,
        disableClose: true,
        width: '400px',
      });

      const result = (await dialogRef.afterClosed().toPromise()) as RecoveryDialogResult;

      if (result?.action === 'resume' && result.captureState) {
        // Resume from saved state
        this._restoredCaptureState.set(result.captureState);
        this._showGuidedCapture.set(true);
        this._guestPhotoWorkflowState.set('local_draft_resumable');
        console.log('[GuestCheckIn] Resuming from saved state');
      } else if (result?.action === 'takeover' && result.captureState) {
        // Takeover from another tab
        this._restoredCaptureState.set(result.captureState);
        this._showGuidedCapture.set(true);
        this._guestPhotoWorkflowState.set('local_draft_resumable');
        console.log('[GuestCheckIn] Took over session from another tab');
      } else if (result?.action === 'start-fresh') {
        this._guestPhotoWorkflowState.set('not_started');
        console.log('[GuestCheckIn] Starting fresh');
      }
      // 'cancel' action - do nothing, stay on page
    }
  }

  private async reconcileGuestPhotoStateFromServer(): Promise<void> {
    if (!this.persistenceService.isReady) {
      return;
    }

    const status = this._status();
    if (!status?.guestPhotoVerificationEnabled) {
      this._guestPhotoWorkflowState.set('not_started');
      this._preCompletedGuestPhotoTypes.set([]);
      return;
    }

    if (status.guestPhotosConfirmedComplete) {
      await this.persistenceService.deleteCaptureState(this.bookingId, 'guest-checkin');
      this._pendingGuestSubmission.set(null);
      this._restoredCaptureState.set(undefined);
      this._showGuidedCapture.set(false);
      this._guestPhotoWorkflowState.set('uploaded_confirmed');
      await this.loadConfirmedGuestPhotos();
      return;
    }

    if ((status.guestConfirmedPhotoCount ?? 0) > 0) {
      await this.loadConfirmedGuestPhotos();
    }

    if (this._guestPhotoWorkflowState() !== 'uploading') {
      const savedState = await this.persistenceService.loadCaptureState(this.bookingId, 'guest-checkin');
      if (savedState?.capturedPhotos.length) {
        this._restoredCaptureState.set(savedState);
        if (this._guestPhotoWorkflowState() !== 'upload_failed_retryable') {
          this._guestPhotoWorkflowState.set('local_draft_resumable');
        }
      } else if ((status.guestConfirmedPhotoCount ?? 0) === 0) {
        this._guestPhotoWorkflowState.set('not_started');
      }
    }
  }

  private async loadConfirmedGuestPhotos(): Promise<void> {
    try {
      const photos = await firstValueFrom(this.checkInService.getGuestPhotos(this.bookingId));
      this._guestCapturedPhotos.set(photos);
      this._preCompletedGuestPhotoTypes.set(photos.map((photo) => photo.photoType));
    } catch (error) {
      console.warn('[GuestCheckIn] Failed to load confirmed guest photos:', error);
    }
  }

  private hasDraftReadyForUpload(): boolean {
    const pendingSubmission = this._pendingGuestSubmission();
    if (pendingSubmission && pendingSubmission.photos.length > 0) {
      return true;
    }

    const restoredState = this._restoredCaptureState();
    return (restoredState?.capturedPhotos.length ?? 0) >= 8;
  }

  private async buildSubmissionFromPersistedDraft(): Promise<GuestCheckInPhotoSubmissionDTO | null> {
    const captureState =
      this._restoredCaptureState() ??
      (await this.persistenceService.loadCaptureState(this.bookingId, 'guest-checkin'));
    if (!captureState?.capturedPhotos.length) {
      return null;
    }

    const confirmedTypes = new Set(this._preCompletedGuestPhotoTypes());
    const photos = captureState.capturedPhotos
      .filter((photo) => !confirmedTypes.has(photo.photoType))
      .map((photo) => ({
        photoType: photo.photoType,
        base64Data: photo.base64Data,
        filename: `${photo.photoType}_${Date.now()}.jpg`,
        mimeType: photo.mimeType,
        capturedAt: photo.capturedAt,
      }));

    if (photos.length === 0) {
      return null;
    }

    return {
      photos,
      clientCapturedAt: new Date().toISOString(),
      deviceInfo: navigator.userAgent,
    };
  }

  /**
   * Persist form state to IndexedDB.
   */
  private async persistFormState(): Promise<void> {
    if (!this.bookingId) return;

    await this.persistenceService.saveFormState(this.bookingId, 'guest-checkin', {
      conditionAccepted: this._conditionAccepted(),
      conditionComment: this.conditionForm.get('conditionComment')?.value || undefined,
    });
  }

  /**
   * Restore form state from IndexedDB.
   */
  private async restoreFormState(): Promise<void> {
    const formState = await this.persistenceService.loadFormState(this.bookingId, 'guest-checkin');

    if (formState) {
      if (formState.conditionAccepted !== undefined) {
        this.conditionForm.patchValue({ conditionAccepted: formState.conditionAccepted });
      }
      if (formState.conditionComment) {
        this.conditionForm.patchValue({ conditionComment: formState.conditionComment });
      }
      console.log('[GuestCheckIn] Restored form state');
    }
  }

  // Computed
  vehicleTitle = computed(() => {
    const car = this._status()?.car;
    if (!car) return 'Vozilo';
    return `${car.brand} ${car.model} (${car.year})`;
  });

  /**
   * Computed signal for pickup location data.
   * Maps CheckInStatusDTO fields to PickupLocationData interface.
   */
  pickupLocationData = computed<PickupLocationData | null>(() => {
    const s = this._status();
    if (!s?.pickupLatitude || !s?.pickupLongitude) {
      return null;
    }
    return {
      latitude: s.pickupLatitude,
      longitude: s.pickupLongitude,
      address: s.pickupAddress,
      city: s.pickupCity,
      zipCode: s.pickupZipCode,
      isEstimated: s.estimatedLocation,
    };
  });

  /**
   * Check if any host photos have validation issues (rejected status).
   * Used to show warning banner to guest about incomplete documentation.
   */
  hasPhotosWithIssues(): boolean {
    const photos = this._status()?.vehiclePhotos;
    if (!photos || photos.length === 0) return false;
    return photos.some(
      (photo: CheckInPhotoDTO) =>
        photo.exifValidationStatus?.startsWith('REJECTED') || photo.accepted === false,
    );
  }

  /**
   * Check if location requirement is bypassed in development.
   * Uses environment.checkIn.requireLocation setting.
   */
  protected readonly locationRequired = environment.checkIn?.requireLocation !== false;

  canSubmit = computed(() => {
    // In development, bypass location check if requireLocation is false
    const hasPosition = this.locationRequired ? this.geolocationService.hasPosition() : true;
    const isLoading = this.checkInService.isLoading();
    const accepted = this._conditionAccepted();
    const hasComment = !!this.conditionForm.get('conditionComment')?.value?.trim();

    // Can submit if: has location (or bypassed), not loading, and either accepted condition or has comment about issues
    return hasPosition && !isLoading && (accepted || hasComment);
  });

  // Photo labels
  private photoLabels: Record<string, string> = {
    HOST_EXTERIOR_FRONT: 'Prednja strana',
    HOST_EXTERIOR_REAR: 'Zadnja strana',
    HOST_EXTERIOR_LEFT: 'Leva strana',
    HOST_EXTERIOR_RIGHT: 'Desna strana',
    HOST_INTERIOR_DASHBOARD: 'Instrument tabla',
    HOST_INTERIOR_REAR: 'Zadnja sedišta',
    HOST_ODOMETER: 'Kilometraža',
    HOST_FUEL_GAUGE: 'Nivo goriva',
    HOST_DAMAGE_PREEXISTING: 'Postojeće oštećenje',
  };

  getPhotoLabel(photoType: string): string {
    return this.photoLabels[photoType] ?? photoType;
  }

  /**
   * Transform storage URL to proper API URL for serving photos.
   *
   * The backend now returns Supabase signed URLs (https://...) for all photos.
   * This method handles both:
   *   - Signed URLs (absolute) — returned as-is
   *   - Legacy storage key paths — proxied through backend API endpoints
   */
  getPhotoUrl(photo: CheckInPhotoDTO): string {
    const url = photo.url;

    // If URL is already absolute (signed URL from Supabase), return as-is
    if (url && url.startsWith('http')) {
      return url;
    }

    // Legacy fallback: transform storage path to API URL
    if (url) {
      const baseUrl = environment.baseApiUrl.replace(/\/$/, '');

      // Strip "checkin/" prefix if present (host storage key format)
      const pathSegment = url.replace(/^checkin\//, '');
      return `${baseUrl}/checkin/photos/${pathSegment}`;
    }

    return '';
  }

  openPhotoViewer(photo: CheckInPhotoDTO): void {
    const photos: CheckInPhotoDTO[] = this._status()?.vehiclePhotos || [];
    const startIndex = photos.findIndex((p: CheckInPhotoDTO) => p.photoId === photo.photoId);

    this.dialog.open(PhotoViewerDialogComponent, {
      data: {
        photos: photos.map((p: CheckInPhotoDTO) => ({
          ...p,
          url: this.getPhotoUrl(p),
        })),
        startIndex: startIndex >= 0 ? startIndex : 0,
      },
      panelClass: 'photo-viewer-dialog-panel',
      maxWidth: '100vw',
      maxHeight: '100vh',
      width: '100vw',
      height: '100vh',
    });
  }

  revealLockboxCode(): void {
    this.checkInService.revealLockboxCode(this.bookingId).subscribe({
      next: (result) => {
        this._lockboxCode.set(result.lockboxCode);
        this.snackBar.open('Lockbox kod je prikazan', '', { duration: 2000 });
      },
      error: (err) => {
        const message = err.error?.message || 'Nije moguće prikazati kod. Proverite lokaciju.';
        this.snackBar.open(message, 'OK', { duration: 5000 });
      },
    });
  }

  submitAcknowledgment(): void {
    const accepted = this.conditionForm.get('conditionAccepted')?.value || false;
    const comment = this.conditionForm.get('conditionComment')?.value || undefined;

    // VAL-004: When guest has NOT accepted condition but provided a comment,
    // this is a damage dispute — send the dispute fields so the backend
    // routes to handleCheckInDispute() instead of throwing an error.
    const isDispute = !accepted && !!comment?.trim();
    const disputeFields = isDispute
      ? {
          disputePreExistingDamage: true,
          damageDisputeDescription: comment!.trim(),
        }
      : undefined;

    // With dual-party photos, hotspots are no longer used - photos serve as documentation
    this.checkInService
      .acknowledgeCondition(
        this.bookingId,
        accepted,
        comment,
        [], // No hotspots - dual-party photos now serve as documentation
        disputeFields,
      )
      .subscribe({
        next: () => {
          // Clear persisted session data on successful submission
          this.persistenceService.clearBookingData(this.bookingId, 'guest-checkin').catch((err) => {
            console.warn('[GuestCheckIn] Failed to clear persistence data:', err);
          });
          this._hasUnsavedPhotos.set(false);

          const message = accepted ? 'Stanje vozila potvrđeno!' : 'Prijava oštećenja poslata!';
          this.snackBar.open(message, 'OK', {
            duration: 3000,
            panelClass: 'success-snackbar',
          });
          this.completed.emit();
        },
        error: (err) => {
          const message = err.error?.message || 'Potvrda nije uspela. Pokušajte ponovo.';
          this.snackBar.open(message, 'OK', { duration: 5000 });
        },
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DUAL-PARTY PHOTO CAPTURE METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Start the guided photo capture flow.
   * Loads the guest check-in sequence from the backend.
   */
  startGuidedCapture(): void {
    this._guestPhotoWorkflowState.set('local_draft_resumable');
    this._showGuidedCapture.set(true);
  }

  continueGuestPhotoFlow(): void {
    if (this.hasDraftReadyForUpload()) {
      void this.retryPendingGuestPhotoUpload();
      return;
    }

    this.startGuidedCapture();
  }

  private async retryPendingGuestPhotoUpload(): Promise<void> {
    const submission = this._pendingGuestSubmission() ?? (await this.buildSubmissionFromPersistedDraft());
    if (!submission) {
      this.startGuidedCapture();
      return;
    }

    this.submitGuestPhotoSubmission(submission);
  }

  /**
   * Skip guest photo capture (only available when not required).
   */
  skipGuestPhotos(): void {
    this._guestPhotosSkipped.set(true);
    this.snackBar.open('Fotografije preskočene. Možete nastaviti sa potvrdom.', 'OK', {
      duration: 3000,
    });
  }

  /**
   * Handle completion of guided photo capture.
   * Converts submission data to CheckInPhotoDTO format for comparison component.
   * Stores full base64 data URLs so photos can be displayed locally before upload.
   * @param submission The submitted photo data from guided capture
   */
  onGuestPhotosComplete(submission: GuestCheckInPhotoSubmissionDTO): void {
    // Show immediate feedback with local previews while uploading
    const localPhotos: CheckInPhotoDTO[] = submission.photos.map((p, i) => ({
      photoId: -(i + 1), // Negative temporary IDs
      photoType: p.photoType,
      url: `data:${p.mimeType || 'image/jpeg'};base64,${p.base64Data}`,
      uploadedAt: new Date().toISOString(),
      exifValidationStatus: 'PENDING' as const,
      exifValidationMessage: null,
      width: null,
      height: null,
      mimeType: p.mimeType || 'image/jpeg',
      exifTimestamp: null,
      exifLatitude: p.latitude ?? null,
      exifLongitude: p.longitude ?? null,
      deviceModel: null,
      accepted: true,
    }));

    // Set local previews immediately for responsive UI
    this._guestCapturedPhotos.set(localPhotos);
    this._showGuidedCapture.set(false);

    this.submitGuestPhotoSubmission(submission);
  }

  private submitGuestPhotoSubmission(submission: GuestCheckInPhotoSubmissionDTO): void {
    this._pendingGuestSubmission.set(submission);
    this._guestPhotoWorkflowState.set('uploading');
    this.snackBar.open(`Otpremanje ${submission.photos.length} fotografija...`, '', {
      duration: 0,
    });

    this.checkInService.uploadGuestPhotos(this.bookingId, submission).subscribe({
      next: (response) => {
        if (response.success && response.processedPhotos?.length > 0 && response.rejectedCount === 0) {
          // Replace local previews with backend-confirmed photos (with signed URLs)
          const confirmedPhotos: CheckInPhotoDTO[] = response.processedPhotos.map((p) => ({
            photoId: p.photoId ?? 0,
            photoType: p.photoType,
            url: p.url ?? '',
            uploadedAt: new Date().toISOString(),
            exifValidationStatus: (p.exifValidationStatus as ExifValidationStatus) ?? 'VALID',
            exifValidationMessage: null,
            width: null,
            height: null,
            mimeType: 'image/jpeg',
            exifTimestamp: null,
            exifLatitude: null,
            exifLongitude: null,
            deviceModel: null,
            accepted: p.accepted,
          }));
          this._guestCapturedPhotos.set(confirmedPhotos);
        }

        this.snackBar.dismiss();
        if (response.guestPhotosComplete && response.rejectedCount === 0) {
          this._pendingGuestSubmission.set(null);
          this._guestPhotoWorkflowState.set('uploaded_confirmed');
          this._preCompletedGuestPhotoTypes.set(response.processedPhotos.map((photo) => photo.photoType));
          this.persistenceService
            .deleteCaptureState(this.bookingId, 'guest-checkin')
            .catch((err) => console.warn('[GuestCheckIn] Failed to clear capture state:', err));
          this.snackBar.open(`${response.acceptedCount} fotografija uspešno otpremljeno!`, 'OK', {
            duration: 3000,
            panelClass: 'success-snackbar',
          });
        } else {
          this._guestPhotoWorkflowState.set('upload_failed_retryable');
          void this.loadConfirmedGuestPhotos();
          this.snackBar.open(response.userMessage, 'OK', {
            duration: 5000,
          });
          console.warn(`[GuestCheckIn] Guest photo upload incomplete: ${response.rejectedCount} rejected`);
        }
      },
      error: (err) => {
        console.error('[GuestCheckIn] Failed to upload photos to backend:', err);
        this._guestPhotoWorkflowState.set('upload_failed_retryable');
        this.snackBar.dismiss();
        this.snackBar.open(
          'Greška pri otpremanju fotografija. Sačuvani nacrt je dostupan za ponovni pokušaj.',
          'OK',
          { duration: 5000 },
        );
      },
    });
  }

  /**
   * Handle cancellation of photo capture.
   */
  onGuestPhotosCancelled(): void {
    this._showGuidedCapture.set(false);
    if (!this.guestPhotosComplete() && this._guestCapturedPhotos().length > 0) {
      this._guestPhotoWorkflowState.set('local_draft_resumable');
    }
    this.photoGuidanceService.resetCapture();
  }

  /**
   * Continue after photo comparison.
   */
  onComparisonContinue(): void {
    // User is satisfied with the comparison, proceed to condition acknowledgment
    console.log('[GuestCheckIn] Comparison complete, proceeding to acknowledgment');
    // Collapse the comparison view after confirmation
    this._showPhotoComparison.set(false);
  }

  /**
   * Toggle photo comparison visibility (privacy-first: opt-in).
   */
  togglePhotoComparison(): void {
    this._showPhotoComparison.update((v) => !v);
  }

  /**
   * Report a discrepancy found during photo comparison.
   * Expands the comment field for the user to describe the issue.
   */
  onReportDiscrepancy(): void {
    // Show the comment section for user to describe the issue
    this.snackBar.open('Razlike primećene. Opišite problem u polju ispod.', 'OK', {
      duration: 4000,
      panelClass: 'warning-snackbar',
    });

    // Uncheck condition accepted to reveal the comment field
    this.conditionForm.patchValue({ conditionAccepted: false });
  }
}