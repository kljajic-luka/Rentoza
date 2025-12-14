import { Component, Input, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpEventType } from '@angular/common/http';
import { CarDocumentService, DocumentType, CarDocument } from '../../../../core/services/car-document.service';

/**
 * Document upload component with drag-and-drop support.
 * 
 * Features:
 * - Drag-and-drop file upload
 * - File type validation (PDF, images)
 * - Upload progress indicator
 * - Image preview
 */
@Component({
  selector: 'app-document-upload',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './document-upload.component.html',
  styleUrls: ['./document-upload.component.scss']
})
export class DocumentUploadComponent {
  @Input() carId?: number;
  @Input() documentType: DocumentType = 'REGISTRATION';
  @Input() label: string = 'Otpremite dokument';
  @Input() required: boolean = true;
  
  @Output() uploaded = new EventEmitter<CarDocument>();
  @Output() fileSelected = new EventEmitter<File>();
  @Output() removed = new EventEmitter<void>();

  // State
  file = signal<File | null>(null);
  previewUrl = signal<string | null>(null);
  error = signal<string | null>(null);
  uploadProgress = signal<number>(0);
  uploading = signal<boolean>(false);
  expiryDate = signal<string>('');
  dragOver = signal<boolean>(false);

  private readonly allowedTypes = [
    'application/pdf',
    'image/png',
    'image/jpeg',
    'image/jpg'
  ];
  private readonly maxSize = 10 * 1024 * 1024; // 10MB

  constructor(private documentService: CarDocumentService) {}

  // ==================== DRAG & DROP ====================

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.dragOver.set(false);

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.handleFile(files[0]);
    }
  }

  // ==================== FILE INPUT ====================

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.handleFile(input.files[0]);
    }
  }

  private handleFile(file: File): void {
    // Reset state
    this.error.set(null);
    this.previewUrl.set(null);

    // Validate type
    if (!this.allowedTypes.includes(file.type)) {
      this.error.set('Dozvoljeni formati: PDF, JPEG, PNG');
      return;
    }

    // Validate size
    if (file.size > this.maxSize) {
      this.error.set('Maksimalna veličina: 10MB');
      return;
    }

    this.file.set(file);

    // Create preview for images
    if (file.type.startsWith('image/')) {
      const reader = new FileReader();
      reader.onload = () => {
        this.previewUrl.set(reader.result as string);
      };
      reader.readAsDataURL(file);
    }
    
    this.fileSelected.emit(file);
  }

  // ==================== UPLOAD ====================

  upload(): void {
    const currentFile = this.file();
    if (!currentFile || !this.carId) return;

    this.uploading.set(true);
    this.uploadProgress.set(0);
    this.error.set(null);

    this.documentService.uploadDocument(
      this.carId,
      currentFile,
      this.documentType,
      this.expiryDate() || undefined
    ).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress) {
          const progress = event.total
            ? Math.round((100 * event.loaded) / event.total)
            : 0;
          this.uploadProgress.set(progress);
        } else if (event.type === HttpEventType.Response) {
          this.uploading.set(false);
          this.uploadProgress.set(100);
          if (event.body) {
            this.uploaded.emit(event.body);
          }
        }
      },
      error: (err) => {
        this.uploading.set(false);
        this.error.set(err.error?.message || 'Greška pri otpremanju');
        console.error('Upload error:', err);
      }
    });
  }

  // ==================== HELPERS ====================

  remove(): void {
    this.file.set(null);
    this.previewUrl.set(null);
    this.error.set(null);
    this.uploadProgress.set(0);
    this.removed.emit();
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  getDocumentIcon(): string {
    const file = this.file();
    if (!file) return 'file';
    if (file.type === 'application/pdf') return 'file-pdf';
    if (file.type.startsWith('image/')) return 'image';
    return 'file';
  }
}
