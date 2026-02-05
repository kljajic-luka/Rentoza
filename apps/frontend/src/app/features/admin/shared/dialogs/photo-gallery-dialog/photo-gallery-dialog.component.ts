import { Component, Inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';

export interface PhotoGalleryDialogData {
  title: string;
  photoGroups: PhotoGroup[];
}

export interface PhotoGroup {
  label: string;
  photoUrls: string[];
}

@Component({
  selector: 'app-photo-gallery-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTabsModule,
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon class="dialog-icon">photo_library</mat-icon>
      {{ data.title }}
    </h2>

    <mat-dialog-content class="gallery-content">
      <mat-tab-group>
        @for (group of data.photoGroups; track group.label) {
          <mat-tab [label]="group.label + ' (' + group.photoUrls.length + ')'">
            <div class="photo-grid">
              @for (url of group.photoUrls; track url; let i = $index) {
                <div class="photo-item" (click)="openFullscreen(group, i)">
                  <img [src]="url" [alt]="group.label + ' photo ' + (i + 1)" loading="lazy" />
                  <div class="photo-overlay">
                    <mat-icon>zoom_in</mat-icon>
                  </div>
                </div>
              }
              @if (group.photoUrls.length === 0) {
                <div class="empty-state">
                  <mat-icon>photo_camera</mat-icon>
                  <p>No photos in this category</p>
                </div>
              }
            </div>
          </mat-tab>
        }
      </mat-tab-group>

      <!-- Fullscreen View -->
      @if (fullscreenMode()) {
        <div class="fullscreen-overlay" (click)="closeFullscreen()">
          <div class="fullscreen-container" (click)="$event.stopPropagation()">
            <button mat-icon-button class="close-btn" (click)="closeFullscreen()">
              <mat-icon>close</mat-icon>
            </button>

            <button
              mat-icon-button
              class="nav-btn prev"
              (click)="previousPhoto()"
              [disabled]="currentIndex() === 0"
            >
              <mat-icon>chevron_left</mat-icon>
            </button>

            <img [src]="currentPhoto()" alt="Fullscreen photo" class="fullscreen-image" />

            <button
              mat-icon-button
              class="nav-btn next"
              (click)="nextPhoto()"
              [disabled]="currentIndex() >= currentGroup()!.photoUrls.length - 1"
            >
              <mat-icon>chevron_right</mat-icon>
            </button>

            <div class="photo-counter">
              {{ currentIndex() + 1 }} / {{ currentGroup()!.photoUrls.length }}
            </div>
          </div>
        </div>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button (click)="onClose()">Close</button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .dialog-icon {
        vertical-align: middle;
        margin-right: 8px;
        color: #1976d2;
      }

      .gallery-content {
        min-width: 600px;
        min-height: 400px;
        position: relative;
      }

      .photo-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
        gap: 12px;
        padding: 16px 0;
      }

      .photo-item {
        position: relative;
        aspect-ratio: 4/3;
        border-radius: 8px;
        overflow: hidden;
        cursor: pointer;
        background: #f5f5f5;
      }

      .photo-item img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        transition: transform 0.2s;
      }

      .photo-item:hover img {
        transform: scale(1.05);
      }

      .photo-overlay {
        position: absolute;
        inset: 0;
        background: rgba(0, 0, 0, 0.3);
        display: flex;
        align-items: center;
        justify-content: center;
        opacity: 0;
        transition: opacity 0.2s;
      }

      .photo-item:hover .photo-overlay {
        opacity: 1;
      }

      .photo-overlay mat-icon {
        color: white;
        font-size: 32px;
        width: 32px;
        height: 32px;
      }

      .empty-state {
        grid-column: 1 / -1;
        text-align: center;
        padding: 40px;
        color: #999;
      }

      .empty-state mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        margin-bottom: 8px;
      }

      /* Fullscreen overlay */
      .fullscreen-overlay {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.95);
        z-index: 1000;
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .fullscreen-container {
        position: relative;
        max-width: 90vw;
        max-height: 90vh;
      }

      .fullscreen-image {
        max-width: 100%;
        max-height: 85vh;
        object-fit: contain;
      }

      .close-btn {
        position: absolute;
        top: -40px;
        right: -40px;
        color: white;
      }

      .nav-btn {
        position: absolute;
        top: 50%;
        transform: translateY(-50%);
        color: white;
        background: rgba(255, 255, 255, 0.1);
      }

      .nav-btn.prev {
        left: -60px;
      }

      .nav-btn.next {
        right: -60px;
      }

      .nav-btn:disabled {
        opacity: 0.3;
      }

      .photo-counter {
        position: absolute;
        bottom: -30px;
        left: 50%;
        transform: translateX(-50%);
        color: white;
        font-size: 14px;
      }
    `,
  ],
})
export class PhotoGalleryDialogComponent {
  fullscreenMode = signal(false);
  currentGroup = signal<PhotoGroup | null>(null);
  currentIndex = signal(0);

  currentPhoto = computed(() => {
    const group = this.currentGroup();
    const index = this.currentIndex();
    return group ? group.photoUrls[index] : '';
  });

  constructor(
    public dialogRef: MatDialogRef<PhotoGalleryDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: PhotoGalleryDialogData,
  ) {}

  openFullscreen(group: PhotoGroup, index: number): void {
    this.currentGroup.set(group);
    this.currentIndex.set(index);
    this.fullscreenMode.set(true);
  }

  closeFullscreen(): void {
    this.fullscreenMode.set(false);
  }

  previousPhoto(): void {
    if (this.currentIndex() > 0) {
      this.currentIndex.update((i) => i - 1);
    }
  }

  nextPhoto(): void {
    const group = this.currentGroup();
    if (group && this.currentIndex() < group.photoUrls.length - 1) {
      this.currentIndex.update((i) => i + 1);
    }
  }

  onClose(): void {
    this.dialogRef.close();
  }
}
