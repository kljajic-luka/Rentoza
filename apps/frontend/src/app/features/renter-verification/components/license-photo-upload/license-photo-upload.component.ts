import {
  Component,
  Input,
  Output,
  EventEmitter,
  signal,
  computed,
  inject,
  ChangeDetectionStrategy,
  OnDestroy,
  ElementRef,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

/**
 * License Photo Upload Component
 *
 * A specialized wrapper component for driver license document uploads.
 * Enforces license-specific validation rules while reusing proven upload patterns.
 *
 * Features:
 * - Drag & drop support
 * - Client-side file validation (size, type, dimensions)
 * - Image preview with secure handling
 * - PII-safe design (no local storage, previews revoked on destroy)
 * - Accessibility support (ARIA labels, keyboard navigation)
 *
 * Security Notes:
 * - Files are NOT converted to base64
 * - Previews use Object URLs (revoked on component destroy)
 * - No file data logged or stored locally
 *
 * @example
 * ```html
 * <app-license-photo-upload
 *   side="front"
 *   label="Prednja strana"
 *   (fileSelected)="onFrontSelected($event)"
 *   (removed)="onFrontRemoved()">
 * </app-license-photo-upload>
 * ```
 */
@Component({
  selector: 'app-license-photo-upload',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './license-photo-upload.component.html',
  styleUrls: ['./license-photo-upload.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LicensePhotoUploadComponent implements OnDestroy {
  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;

  // ============================================================================
  // INPUTS
  // ============================================================================

  /** Which side of the license this uploader is for */
  @Input() side: 'front' | 'back' = 'front';

  /** Label shown above the upload area */
  @Input() label = 'Otpremite sliku';

  /** Whether this field is required */
  @Input() required = true;

  /** Maximum file size in bytes (default 10MB) */
  @Input() maxSizeBytes = 10 * 1024 * 1024;

  /** Allowed MIME types */
  @Input() allowedTypes = ['image/jpeg', 'image/jpg', 'image/png'];

  /** Minimum image width for quality (default 640px) */
  @Input() minWidth = 640;

  /** Minimum image height for quality (default 480px) */
  @Input() minHeight = 480;

  /** Whether to show dimension warning (soft validation) */
  @Input() showDimensionWarning = true;

  // ============================================================================
  // OUTPUTS
  // ============================================================================

  /** Emitted when a valid file is selected */
  @Output() fileSelected = new EventEmitter<File>();

  /** Emitted when the file is removed/cleared */
  @Output() removed = new EventEmitter<void>();

  /** Emitted on validation error */
  @Output() validationError = new EventEmitter<string>();

  // ============================================================================
  // STATE SIGNALS
  // ============================================================================

  /** Currently selected file */
  readonly file = signal<File | null>(null);

  /** Preview URL for the image */
  readonly previewUrl = signal<string | null>(null);

  /** Current error message */
  readonly error = signal<string | null>(null);

  /** Warning message (non-blocking) */
  readonly warning = signal<string | null>(null);

  /** Is drag over the drop zone */
  readonly isDragOver = signal<boolean>(false);

  /** Is validating image dimensions */
  readonly isValidating = signal<boolean>(false);

  // ============================================================================
  // COMPUTED
  // ============================================================================

  /** Whether a file has been selected */
  readonly hasFile = computed(() => this.file() !== null);

  /** Max size in MB for display */
  readonly maxSizeMB = computed(() => this.maxSizeBytes / (1024 * 1024));

  /** Allowed types formatted for display */
  readonly allowedTypesDisplay = computed(() => {
    return this.allowedTypes.map((t) => t.replace('image/', '').toUpperCase()).join(', ');
  });

  /** Accept attribute for file input */
  readonly acceptTypes = computed(() => this.allowedTypes.join(','));

  /** Aria label for accessibility */
  readonly ariaLabel = computed(() => {
    const sideLabel = this.side === 'front' ? 'prednju stranu' : 'zadnju stranu';
    return `Otpremite ${sideLabel} vozačke dozvole`;
  });

  // ============================================================================
  // LIFECYCLE
  // ============================================================================

  ngOnDestroy(): void {
    // SECURITY: Revoke preview URL to free memory and prevent leaks
    const url = this.previewUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
  }

  // ============================================================================
  // FILE INPUT HANDLERS
  // ============================================================================

  /**
   * Open file dialog programmatically.
   */
  openFileDialog(): void {
    this.fileInput?.nativeElement?.click();
  }

  /**
   * Handle file selection from input.
   */
  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];

    if (file) {
      this.handleFile(file);
    }

    // Reset input to allow re-selecting same file
    input.value = '';
  }

  // ============================================================================
  // DRAG & DROP HANDLERS
  // ============================================================================

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(false);

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.handleFile(files[0]);
    }
  }

  // ============================================================================
  // FILE HANDLING
  // ============================================================================

  /**
   * Process and validate selected file.
   */
  private handleFile(file: File): void {
    // Clear previous state
    this.clearState();

    // Validate file
    const validationError = this.validateFile(file);
    if (validationError) {
      this.error.set(validationError);
      this.validationError.emit(validationError);
      return;
    }

    // Set file and generate preview
    this.file.set(file);
    this.generatePreview(file);

    // Check image dimensions (soft validation - warning only)
    if (this.showDimensionWarning) {
      this.validateImageDimensions(file);
    }

    // Emit selected file
    this.fileSelected.emit(file);
  }

  /**
   * Validate file against constraints.
   */
  private validateFile(file: File): string | null {
    // Check file size
    if (file.size > this.maxSizeBytes) {
      return `Fajl je prevelik. Maksimalna veličina: ${this.maxSizeMB()}MB`;
    }

    // Check MIME type
    if (!this.allowedTypes.includes(file.type)) {
      return `Nedozvoljen format. Dozvoljeni: ${this.allowedTypesDisplay()}`;
    }

    // Check if actually an image
    if (!file.type.startsWith('image/')) {
      return 'Molimo izaberite sliku';
    }

    return null;
  }

  /**
   * Validate image dimensions (soft check - warning only).
   */
  private validateImageDimensions(file: File): void {
    this.isValidating.set(true);

    const img = new Image();
    img.onload = () => {
      this.isValidating.set(false);

      if (img.width < this.minWidth || img.height < this.minHeight) {
        this.warning.set(
          `Slika ima nisku rezoluciju (${img.width}x${img.height}). ` +
            `Preporučeno: najmanje ${this.minWidth}x${this.minHeight}px za bolji OCR.`
        );
      }

      // Clean up
      URL.revokeObjectURL(img.src);
    };

    img.onerror = () => {
      this.isValidating.set(false);
      URL.revokeObjectURL(img.src);
    };

    img.src = URL.createObjectURL(file);
  }

  /**
   * Generate preview URL for the image.
   */
  private generatePreview(file: File): void {
    // Revoke previous preview URL
    const previousUrl = this.previewUrl();
    if (previousUrl) {
      URL.revokeObjectURL(previousUrl);
    }

    // Generate new preview
    const url = URL.createObjectURL(file);
    this.previewUrl.set(url);
  }

  // ============================================================================
  // PUBLIC METHODS
  // ============================================================================

  /**
   * Remove selected file and clear state.
   */
  remove(): void {
    this.clearState();
    this.removed.emit();
  }

  /**
   * Get the currently selected file.
   */
  getFile(): File | null {
    return this.file();
  }

  /**
   * Check if component has a valid file.
   */
  isValid(): boolean {
    return this.hasFile() && !this.error();
  }

  // ============================================================================
  // PRIVATE HELPERS
  // ============================================================================

  /**
   * Clear all component state.
   */
  private clearState(): void {
    // Revoke preview URL
    const url = this.previewUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }

    // Reset signals
    this.file.set(null);
    this.previewUrl.set(null);
    this.error.set(null);
    this.warning.set(null);
    this.isValidating.set(false);
  }

  // ============================================================================
  // UTILITY
  // ============================================================================

  /**
   * Format file size for display.
   */
  formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}