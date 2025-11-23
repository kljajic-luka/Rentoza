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
   * Generate preview URL from file
   */
  private generatePreview(file: File): void {
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

  /**
   * Upload the selected file to server
   */
  uploadProfilePicture(): void {
    const file = this.selectedFile();

    if (!file || this.isUploading()) {
      return;
    }

    this.isUploading.set(true);
    this.uploadError.set(null);
    this.uploadProgress.set(0);

    this.userService
      .uploadProfilePicture(file)
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
