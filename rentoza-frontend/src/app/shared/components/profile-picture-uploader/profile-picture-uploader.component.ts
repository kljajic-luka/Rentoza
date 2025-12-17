import {
  Component,
  EventEmitter,
  Input,
  Output,
  signal,
  inject,
  ChangeDetectionStrategy,
  ElementRef,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs';
import imageCompression from 'browser-image-compression';

import { UserService, ProfilePictureResult } from '@core/services/user.service';

/**
 * Reusable profile picture uploader component.
 *
 * Features:
 * - Client-side validation (size, MIME type)
 * - Image preview before upload
 * - Upload progress indication
 * - Error handling with user-friendly messages
 * - Accessibility support (ARIA, keyboard navigation)
 *
 * Usage:
 * <app-profile-picture-uploader
 *   [currentAvatarUrl]="user.avatarUrl"
 *   [showCurrentAvatar]="true"
 *   (uploaded)="onAvatarUploaded($event)"
 *   (error)="onUploadError($event)">
 * </app-profile-picture-uploader>
 */
@Component({
  selector: 'app-profile-picture-uploader',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './profile-picture-uploader.component.html',
  styleUrls: ['./profile-picture-uploader.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfilePictureUploaderComponent {
  private readonly userService = inject(UserService);

  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;

  // Configuration inputs - accepts string, null, or undefined for flexibility
  @Input() currentAvatarUrl: string | null | undefined = null;
  @Input() showCurrentAvatar = true;
  @Input() maxSizeBytes = 4 * 1024 * 1024; // 4MB default
  @Input() allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];
  @Input() avatarSize = 120; // px
  @Input() userInitials = '';

  // Output events
  @Output() uploaded = new EventEmitter<string>();
  @Output() error = new EventEmitter<string>();
  @Output() deleted = new EventEmitter<void>();

  // Component state signals
  protected readonly isUploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);
  protected readonly previewUrl = signal<string | null>(null);
  protected readonly selectedFile = signal<File | null>(null);
  protected readonly uploadProgress = signal(0);

  // Computed display values
  protected get acceptTypes(): string {
    return this.allowedTypes.join(',');
  }

  protected get maxSizeMB(): number {
    return this.maxSizeBytes / (1024 * 1024);
  }

  protected get displayAvatarUrl(): string | null {
    return this.previewUrl() || this.currentAvatarUrl || null;
  }

  protected get hasAvatar(): boolean {
    return !!(this.previewUrl() || this.currentAvatarUrl);
  }

  /**
   * Trigger file input click programmatically
   */
  openFileDialog(): void {
    this.fileInput?.nativeElement?.click();
  }

  /**
   * Handle file selection from input
   */
  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];

    if (!file) {
      return;
    }

    // Reset previous state
    this.uploadError.set(null);

    // Client-side validation
    const validationError = this.validateFile(file);
    if (validationError) {
      this.uploadError.set(validationError);
      this.error.emit(validationError);
      this.resetFileInput();
      return;
    }

    // Store selected file
    this.selectedFile.set(file);

    // Generate preview
    this.generatePreview(file);
  }

  /**
   * Validate file before upload
   */
  private validateFile(file: File): string | null {
    // Check file size
    if (file.size > this.maxSizeBytes) {
      return `Fajl je prevelik. Maksimalna veličina: ${this.maxSizeMB}MB`;
    }

    // Check MIME type
    if (!this.allowedTypes.includes(file.type)) {
      return 'Dozvoljeni formati: JPEG, PNG, WebP';
    }

    // Check if file is actually an image (basic check)
    if (!file.type.startsWith('image/')) {
      return 'Molimo izaberite sliku';
    }

    return null;
  }

  /**
   * Generate preview URL from file with EXIF orientation correction.
   *
   * This fixes the common mobile photo rotation bug where images appear
   * rotated due to EXIF orientation metadata not being respected.
   *
   * Enterprise solution based on Turo/Airbnb patterns:
   * - Reads EXIF orientation metadata
   * - Corrects rotation client-side before preview
   * - Ensures consistent display across all browsers/devices
   */
  private async generatePreview(file: File): Promise<void> {
    try {
      // Get EXIF orientation to detect if rotation correction is needed
      const orientation = await imageCompression.getExifOrientation(file);

      // Orientations 5-8 indicate rotation/mirroring is needed
      const needsOrientationCorrection = orientation > 1;

      if (needsOrientationCorrection) {
        // Use canvas to correct orientation
        const correctedImage = await this.correctImageOrientation(file, orientation);
        this.previewUrl.set(correctedImage);
      } else {
        // No correction needed - use original
        const reader = new FileReader();
        reader.onload = (e) => {
          const result = e.target?.result as string;
          this.previewUrl.set(result);
        };
        reader.onerror = () => {
          this.uploadError.set('Greška pri učitavanju slike');
        };
        reader.readAsDataURL(file);
      }
    } catch (error) {
      console.error('Error generating preview:', error);
      // Fallback to standard preview on error
      const reader = new FileReader();
      reader.onload = (e) => {
        const result = e.target?.result as string;
        this.previewUrl.set(result);
      };
      reader.readAsDataURL(file);
    }
  }

  /**
   * Correct image orientation based on EXIF data.
   *
   * EXIF Orientation values:
   * 1 = Normal (no rotation)
   * 2 = Flip horizontal
   * 3 = Rotate 180°
   * 4 = Flip vertical
   * 5 = Rotate 90° CW + flip horizontal
   * 6 = Rotate 90° CW
   * 7 = Rotate 90° CCW + flip horizontal
   * 8 = Rotate 90° CCW
   */
  private async correctImageOrientation(file: File, orientation: number): Promise<string> {
    return new Promise((resolve, reject) => {
      const img = new Image();
      const reader = new FileReader();

      reader.onload = (e) => {
        img.src = e.target?.result as string;
      };

      img.onload = () => {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');

        if (!ctx) {
          reject(new Error('Could not get canvas context'));
          return;
        }

        let width = img.width;
        let height = img.height;

        // Set canvas dimensions based on orientation
        // Orientations 5-8 swap width/height
        if (orientation >= 5 && orientation <= 8) {
          canvas.width = height;
          canvas.height = width;
        } else {
          canvas.width = width;
          canvas.height = height;
        }

        // Apply transformation based on EXIF orientation
        switch (orientation) {
          case 2:
            // Flip horizontal
            ctx.transform(-1, 0, 0, 1, width, 0);
            break;
          case 3:
            // Rotate 180°
            ctx.transform(-1, 0, 0, -1, width, height);
            break;
          case 4:
            // Flip vertical
            ctx.transform(1, 0, 0, -1, 0, height);
            break;
          case 5:
            // Rotate 90° CW + flip horizontal
            ctx.transform(0, 1, 1, 0, 0, 0);
            break;
          case 6:
            // Rotate 90° CW
            ctx.transform(0, 1, -1, 0, height, 0);
            break;
          case 7:
            // Rotate 90° CCW + flip horizontal
            ctx.transform(0, -1, -1, 0, height, width);
            break;
          case 8:
            // Rotate 90° CCW
            ctx.transform(0, -1, 1, 0, 0, width);
            break;
          default:
            // No transformation needed
            break;
        }

        // Draw image with corrected orientation
        ctx.drawImage(img, 0, 0);

        // Convert canvas to data URL
        resolve(canvas.toDataURL(file.type || 'image/jpeg', 0.95));
      };

      img.onerror = () => {
        reject(new Error('Failed to load image'));
      };

      reader.onerror = () => {
        reject(new Error('Failed to read file'));
      };

      reader.readAsDataURL(file);
    });
  }

  /**
   * Upload the selected file to server with EXIF orientation correction.
   *
   * This ensures images are uploaded in the correct orientation by:
   * 1. Reading EXIF orientation metadata
   * 2. Correcting rotation client-side
   * 3. Stripping EXIF data to prevent re-rotation on backend
   * 4. Compressing to reasonable size for upload
   *
   * Enterprise pattern: Handle orientation on client to reduce backend load
   */
  async uploadProfilePicture(): Promise<void> {
    const file = this.selectedFile();

    if (!file || this.isUploading()) {
      return;
    }

    this.isUploading.set(true);
    this.uploadError.set(null);
    this.uploadProgress.set(0);

    try {
      // Process image: correct orientation, compress, strip EXIF
      const processedFile = await this.processImageForUpload(file);

      this.userService
        .uploadProfilePicture(processedFile)
        .pipe(finalize(() => this.isUploading.set(false)))
        .subscribe({
          next: (result: ProfilePictureResult) => {
            // Clear preview and selection
            this.previewUrl.set(null);
            this.selectedFile.set(null);
            this.resetFileInput();

            // Emit success event with new URL
            this.uploaded.emit(result.profilePictureUrl);
          },
          error: (err) => {
            const message = this.getErrorMessage(err);
            this.uploadError.set(message);
            this.error.emit(message);
          },
        });
    } catch (error) {
      this.isUploading.set(false);
      const message = 'Greška pri obradi slike. Pokušajte ponovo.';
      this.uploadError.set(message);
      this.error.emit(message);
    }
  }

  /**
   * Process image for upload: correct orientation, compress, strip EXIF.
   *
   * This is the enterprise-grade solution for the rotation bug:
   * - Fixes EXIF orientation issues from mobile cameras
   * - Removes EXIF metadata (privacy + prevents re-rotation)
   * - Compresses to reduce upload time and storage
   * - Maintains quality while reducing file size
   */
  private async processImageForUpload(file: File): Promise<File> {
    const options = {
      // Reasonable max size for profile pictures (balance quality vs file size)
      maxSizeMB: 1,
      // Max dimension - backend will resize to 512x512 anyway
      maxWidthOrHeight: 1024,
      // Use web worker for non-blocking compression
      useWebWorker: true,
      // DO NOT preserve EXIF - we're correcting orientation, so strip metadata
      preserveExif: false,
      // Initial quality
      initialQuality: 0.85,
      // Get EXIF orientation to apply correction
      exifOrientation: await imageCompression.getExifOrientation(file),
    };

    // Compress and correct orientation in one operation
    // browser-image-compression automatically applies orientation correction
    // when exifOrientation is provided and preserveExif is false
    const processedFile = await imageCompression(file, options);

    return processedFile;
  }

  /**
   * Cancel file selection and clear preview
   */
  cancelSelection(): void {
    this.previewUrl.set(null);
    this.selectedFile.set(null);
    this.uploadError.set(null);
    this.resetFileInput();
  }

  /**
   * Delete current profile picture
   */
  deleteCurrentPicture(): void {
    if (this.isUploading()) {
      return;
    }

    this.isUploading.set(true);
    this.uploadError.set(null);

    this.userService
      .deleteProfilePicture()
      .pipe(finalize(() => this.isUploading.set(false)))
      .subscribe({
        next: () => {
          this.deleted.emit();
        },
        error: (err) => {
          const message = this.getErrorMessage(err);
          this.uploadError.set(message);
          this.error.emit(message);
        },
      });
  }

  /**
   * Get user-friendly error message from HTTP error
   */
  private getErrorMessage(err: any): string {
    const status = err?.status;
    const serverMessage = err?.error?.error;

    if (serverMessage) {
      return serverMessage;
    }

    switch (status) {
      case 401:
      case 403:
        return 'Morate biti prijavljeni da biste promenili profilnu sliku';
      case 413:
        return `Fajl je prevelik. Maksimalna veličina: ${this.maxSizeMB}MB`;
      case 415:
        return 'Dozvoljeni formati: JPEG, PNG, WebP';
      case 400:
        return 'Neispravan fajl. Proverite format i veličinu.';
      default:
        return 'Greška na serveru. Pokušajte ponovo.';
    }
  }

  /**
   * Reset file input element
   */
  private resetFileInput(): void {
    if (this.fileInput?.nativeElement) {
      this.fileInput.nativeElement.value = '';
    }
  }

  /**
   * Handle drag over event for drag-and-drop
   */
  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
  }

  /**
   * Handle drop event for drag-and-drop
   */
  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();

    const files = event.dataTransfer?.files;
    if (files?.length) {
      const file = files[0];
      const validationError = this.validateFile(file);

      if (validationError) {
        this.uploadError.set(validationError);
        this.error.emit(validationError);
        return;
      }

      this.selectedFile.set(file);
      this.generatePreview(file);
    }
  }
}
