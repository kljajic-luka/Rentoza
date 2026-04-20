/**
 * Damage Report Step Component
 *
 * Extracted from host-check-in.component.ts (2200+ lines) to follow
 * Angular best practices for component decomposition.
 *
 * Handles:
 * - Display of existing damage photos
 * - Adding new damage photo slots (up to MAX_DAMAGE_PHOTOS)
 * - Photo preview with upload progress
 * - Rejection handling with remediation hints
 *
 * ## Usage
 * ```html
 * <app-damage-report-step
 *   [damageSlots]="damagePhotos()"
 *   [damageSlotViewModels]="damageSlotViewModels()"
 *   [maxDamagePhotos]="maxDamagePhotos"
 *   [readOnly]="readOnly"
 *   (addDamageSlot)="addDamagePhoto()"
 *   (removeDamageSlot)="removeDamagePhoto($event.event, $event.slotId)"
 *   (triggerCapture)="triggerFileInput($event)"
 *   (fileSelected)="onFileSelected($event.event, $event.slotId, $event.photoType)"
 *   (retryUpload)="retryUpload($event.event, $event.slotId)"
 * />
 * ```
 *
 * @see host-check-in.component.ts
 */
import { Component, Input, Output, EventEmitter, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { LazyImgDirective } from '../../../../../shared/directives/lazy-img.directive';
import { DamagePhotoSlot, PhotoSlotViewModel } from '../../../../../core/models/check-in.model';

/**
 * Event payload for file selection events.
 */
export interface FileSelectedEvent {
  event: Event;
  slotId: string;
  photoType: string;
}

/**
 * Event payload for slot removal events.
 */
export interface RemoveSlotEvent {
  event: Event;
  slotId: string;
}

/**
 * Event payload for retry events.
 */
export interface RetryEvent {
  event: Event;
  slotId: string;
}

@Component({
  selector: 'app-damage-report-step',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule, MatProgressBarModule, LazyImgDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!readOnly || damageSlots.length > 0) {
      <div class="damage-section">
        <div class="section-header small">
          <mat-icon>report_problem</mat-icon>
          <div>
            <h3>Postojeća oštećenja {{ readOnly ? '' : '(opciono)' }}</h3>
            <p>
              {{
                readOnly
                  ? 'Dokumentovana oštećenja'
                  : 'Dokumentujte postojeće ogrebotine, ulubljenja i sl.'
              }}
            </p>
          </div>
        </div>

        <!-- Damage photo grid -->
        @if (damageSlotViewModels.length > 0) {
          <div class="photo-grid damage-grid">
            @for (vm of damageSlotViewModels; track damageSlots[$index].id) {
              <div
                class="photo-slot damage-slot"
                [class.completed]="vm.isCompleted"
                [class.uploading]="vm.isUploading"
                [class.rejected]="vm.isRejected"
                [class.location-mismatch]="vm.locationMismatch"
                [class.readonly]="readOnly"
                (click)="readOnly ? null : onTriggerCapture(damageSlots[$index].id)"
              >
                @if (vm.previewUrl) {
                  <img appLazyImg [lazySrc]="vm.previewUrl" alt="Oštećenje" class="photo-preview" />
                  <div class="success-badge">
                    <mat-icon>check_circle</mat-icon>
                  </div>
                  @if (!readOnly) {
                    <button
                      mat-mini-fab
                      color="warn"
                      class="remove-photo-btn"
                      (click)="onRemoveSlot($event, damageSlots[$index].id)"
                      aria-label="Ukloni fotografiju"
                    >
                      <mat-icon>close</mat-icon>
                    </button>
                  }
                } @else {
                  <div class="photo-placeholder">
                    <mat-icon>add_a_photo</mat-icon>
                    <span>Oštećenje</span>
                    <button
                      mat-icon-button
                      class="delete-slot-btn"
                      (click)="onRemoveSlot($event, damageSlots[$index].id)"
                      aria-label="Ukloni slot"
                    >
                      <mat-icon>delete</mat-icon>
                    </button>
                  </div>
                }

                @if (vm.isUploading) {
                  <div class="upload-progress">
                    <mat-progress-bar mode="determinate" [value]="vm.progress"></mat-progress-bar>
                    <span>{{ vm.progress }}%</span>
                  </div>
                }

                @if (vm.isRejected) {
                  <div class="rejection-overlay" [class.location-fraud]="vm.locationMismatch">
                    <mat-icon>{{ vm.locationMismatch ? 'location_off' : 'warning' }}</mat-icon>
                    <span class="rejection-reason">{{ vm.rejectionReason }}</span>
                    @if (vm.distanceMeters) {
                      <span class="distance-badge">{{ vm.distanceMeters }}m od automobila</span>
                    }
                    <span class="rejection-hint">{{ vm.remediationHint }}</span>
                    <button
                      mat-button
                      class="retry-btn rejection-retry"
                      (click)="onRetry($event, damageSlots[$index].id)"
                    >
                      <mat-icon>camera_alt</mat-icon>
                      Ponovo
                    </button>
                  </div>
                }

                <input
                  type="file"
                  accept="image/*"
                  capture="environment"
                  [id]="'file-' + damageSlots[$index].id"
                  (change)="
                    onFileSelect($event, damageSlots[$index].id, damageSlots[$index].photoType)
                  "
                  hidden
                />
              </div>
            }
          </div>
        }

        <!-- Add damage photo button (hidden in readOnly mode) -->
        @if (!readOnly) {
          @if (damageSlots.length < maxDamagePhotos) {
            <button
              mat-stroked-button
              color="accent"
              class="add-damage-btn"
              [class.near-limit]="damageSlots.length >= 8"
              (click)="onAddSlot()"
            >
              <mat-icon>add_a_photo</mat-icon>
              Dodaj fotografiju oštećenja
              @if (damageSlots.length >= 8) {
                <span class="limit-counter">({{ damageSlots.length }}/{{ maxDamagePhotos }})</span>
              }
            </button>
            @if (damageSlots.length >= 8) {
              <p class="damage-warning-hint">
                <mat-icon>info</mat-icon>
                Preostalo još {{ maxDamagePhotos - damageSlots.length }} fotografija
              </p>
            }
          } @else {
            <div class="damage-limit-reached">
              <mat-icon>block</mat-icon>
              <p class="damage-limit-hint">
                Maksimalan broj fotografija oštećenja dostignut ({{ maxDamagePhotos }})
              </p>
            </div>
          }
        }
      </div>
    }
  `,
  styles: [
    `
      .damage-section {
        margin-top: 24px;
        padding: 16px;
        background: rgba(255, 152, 0, 0.08);
        border-radius: 12px;
        border: 1px dashed rgba(255, 152, 0, 0.3);
      }

      .section-header {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        margin-bottom: 16px;
      }

      .section-header mat-icon {
        color: #ff9800;
        font-size: 28px;
        width: 28px;
        height: 28px;
        margin-top: 4px;
      }

      .section-header.small h3 {
        font-size: 1rem;
        font-weight: 600;
        margin: 0;
        color: #333;
      }

      .section-header.small p {
        font-size: 0.85rem;
        color: #666;
        margin: 4px 0 0;
      }

      .photo-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
        gap: 12px;
        margin-bottom: 16px;
      }

      .damage-grid {
        grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
      }

      .photo-slot {
        aspect-ratio: 1;
        border-radius: 12px;
        overflow: hidden;
        position: relative;
        cursor: pointer;
        background: #f5f5f5;
        border: 2px dashed #ccc;
        transition: all 0.2s ease;
      }

      .photo-slot:hover:not(.readonly) {
        border-color: var(--brand-primary);
        background: #e3f2fd;
      }

      .damage-slot {
        min-height: 100px;
      }

      .damage-slot.completed {
        border: 2px solid #ff9800;
        background: #fff3e0;
      }

      .photo-preview {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }

      .success-badge {
        position: absolute;
        top: 8px;
        right: 8px;
        background: #4caf50;
        color: white;
        border-radius: 50%;
        width: 24px;
        height: 24px;
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .success-badge mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }

      .remove-photo-btn {
        position: absolute;
        top: 8px;
        left: 8px;
        width: 28px !important;
        height: 28px !important;
      }

      .remove-photo-btn mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      .photo-placeholder {
        position: absolute;
        inset: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 4px;
        color: #999;
      }

      .photo-placeholder mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
      }

      .photo-placeholder span {
        font-size: 0.75rem;
      }

      .delete-slot-btn {
        position: absolute;
        top: 4px;
        right: 4px;
        width: 24px !important;
        height: 24px !important;
      }

      .delete-slot-btn mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
        color: #f44336;
      }

      .upload-progress {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        padding: 8px;
        background: rgba(0, 0, 0, 0.7);
        color: white;
        text-align: center;
      }

      .upload-progress span {
        font-size: 0.75rem;
        display: block;
        margin-top: 4px;
      }

      .rejection-overlay {
        position: absolute;
        inset: 0;
        background: rgba(244, 67, 54, 0.95);
        color: white;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 8px;
        text-align: center;
      }

      .rejection-overlay.location-fraud {
        background: rgba(156, 39, 176, 0.95);
      }

      .rejection-overlay mat-icon {
        font-size: 24px;
        margin-bottom: 4px;
      }

      .rejection-reason {
        font-size: 0.7rem;
        font-weight: 600;
        margin-bottom: 2px;
      }

      .rejection-hint {
        font-size: 0.65rem;
        opacity: 0.9;
      }

      .distance-badge {
        font-size: 0.65rem;
        background: rgba(0, 0, 0, 0.3);
        padding: 2px 6px;
        border-radius: 4px;
        margin: 4px 0;
      }

      .retry-btn {
        margin-top: 8px;
        font-size: 0.7rem;
        padding: 2px 8px;
        min-height: 28px;
      }

      .retry-btn mat-icon {
        font-size: 14px;
        width: 14px;
        height: 14px;
        margin-right: 4px;
      }

      .add-damage-btn {
        width: 100%;
        margin-top: 8px;
      }

      .add-damage-btn mat-icon {
        margin-right: 8px;
      }

      .add-damage-btn.near-limit {
        border-color: #ff9800;
        color: #ff9800;
      }

      .add-damage-btn .limit-counter {
        margin-left: 8px;
        font-weight: 600;
      }

      .damage-warning-hint {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 8px;
        padding: 8px 12px;
        background: #fff3e0;
        border-radius: 8px;
        color: #e65100;
        font-size: 0.85rem;
      }

      .damage-warning-hint mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      .damage-limit-reached {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 16px;
        background: #fce4ec;
        border-radius: 8px;
        margin-top: 8px;
        color: #c62828;
      }

      .damage-limit-reached mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        margin-bottom: 8px;
      }

      .damage-limit-hint {
        font-size: 0.85rem;
        text-align: center;
        margin: 0;
      }

      .photo-slot.readonly {
        cursor: default;
      }
    `,
  ],
})
export class DamageReportStepComponent {
  /**
   * Array of damage photo slots with IDs and photo types.
   */
  @Input({ required: true }) damageSlots: DamagePhotoSlot[] = [];

  /**
   * View models with computed state (preview URLs, upload progress, etc.)
   */
  @Input({ required: true }) damageSlotViewModels: PhotoSlotViewModel[] = [];

  /**
   * Maximum number of damage photos allowed.
   */
  @Input() maxDamagePhotos = 10;

  /**
   * Whether the component is in read-only mode (guest review).
   */
  @Input() readOnly = false;

  /**
   * Emitted when user clicks "Add damage photo" button.
   */
  @Output() addDamageSlot = new EventEmitter<void>();

  /**
   * Emitted when user removes a damage slot.
   */
  @Output() removeDamageSlot = new EventEmitter<RemoveSlotEvent>();

  /**
   * Emitted when user clicks on a slot to capture a photo.
   */
  @Output() triggerCapture = new EventEmitter<string>();

  /**
   * Emitted when user selects a file.
   */
  @Output() fileSelected = new EventEmitter<FileSelectedEvent>();

  /**
   * Emitted when user retries a failed upload.
   */
  @Output() retryUpload = new EventEmitter<RetryEvent>();

  // ========== EVENT HANDLERS ==========

  protected onAddSlot(): void {
    this.addDamageSlot.emit();
  }

  protected onRemoveSlot(event: Event, slotId: string): void {
    event.stopPropagation();
    this.removeDamageSlot.emit({ event, slotId });
  }

  protected onTriggerCapture(slotId: string): void {
    this.triggerCapture.emit(slotId);
  }

  protected onFileSelect(event: Event, slotId: string, photoType: string): void {
    this.fileSelected.emit({ event, slotId, photoType });
  }

  protected onRetry(event: Event, slotId: string): void {
    event.stopPropagation();
    this.retryUpload.emit({ event, slotId });
  }
}