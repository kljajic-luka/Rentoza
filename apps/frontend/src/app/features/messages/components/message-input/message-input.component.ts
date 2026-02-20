import {
  Component,
  input,
  output,
  signal,
  computed,
  ViewChild,
  ElementRef,
  AfterViewInit,
  ChangeDetectionStrategy,
  OnDestroy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';

/**
 * MessageInputComponent - Presentational Component
 *
 * Enterprise-grade message input with:
 * - Auto-expanding textarea
 * - Character counter
 * - Typing indicator events (debounced)
 * - Send button with loading state
 * - Accessibility support
 */
@Component({
  selector: 'app-message-input',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatButtonModule, MatProgressSpinnerModule],
  template: `
    <div class="input-container" [class.disabled]="disabled()">
      <!-- Attachment preview -->
      <div class="attachment-preview" *ngIf="selectedFile()">
        <div class="attachment-info">
          <mat-icon>{{ getAttachmentIcon() }}</mat-icon>
          <span class="attachment-name">{{ selectedFile()!.name }}</span>
          <span class="attachment-size">{{ formatFileSize(selectedFile()!.size) }}</span>
        </div>
        <button
          type="button"
          class="remove-attachment"
          (click)="removeAttachment()"
          aria-label="Ukloni prilog"
        >
          <mat-icon>close</mat-icon>
        </button>
      </div>

      <div class="input-wrapper">
        <!-- Hidden file input -->
        <input
          #fileInput
          type="file"
          [accept]="ALLOWED_TYPES.join(',')"
          (change)="onFileSelected($event)"
          style="display: none"
        />

        <!-- Attach button -->
        <button
          type="button"
          class="attach-button"
          [disabled]="disabled()"
          (click)="fileInput.click()"
          aria-label="Priložite datoteku"
        >
          <mat-icon>attach_file</mat-icon>
        </button>

        <!-- Auto-expanding textarea -->
        <textarea
          #textareaRef
          class="message-textarea"
          [placeholder]="placeholder()"
          [value]="inputValue()"
          [disabled]="disabled()"
          [maxLength]="maxLength"
          (input)="onInput($event)"
          (keydown.enter)="onEnterPress($any($event))"
          (focus)="onFocus()"
          (blur)="onBlur()"
          rows="1"
          aria-label="Napišite poruku"
        ></textarea>

        <!-- Character counter -->
        <span
          class="char-counter"
          [class.warning]="isNearLimit()"
          [class.danger]="isAtLimit()"
          *ngIf="showCharacterCounter()"
        >
          {{ inputValue().length }}/{{ maxLength }}
        </span>

        <!-- Send button -->
        <button
          type="button"
          class="send-button"
          [disabled]="!canSend()"
          (click)="onSend()"
          aria-label="Pošalji poruku"
        >
          <mat-icon *ngIf="!isSending()">send</mat-icon>
          <mat-spinner *ngIf="isSending()" diameter="20"></mat-spinner>
        </button>
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }

      .input-container {
        padding: 16px 20px;
        background-color: #ffffff;
        border-top: 1px solid #e8e8e8;

        &.disabled {
          opacity: 0.6;
          pointer-events: none;
        }
      }

      .attachment-preview {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 8px 12px;
        margin-bottom: 8px;
        background-color: #e3f2fd;
        border-radius: 8px;
        font-size: 13px;

        .attachment-info {
          display: flex;
          align-items: center;
          gap: 8px;
          overflow: hidden;

          mat-icon {
            font-size: 18px;
            width: 18px;
            height: 18px;
            color: #1976d2;
          }

          .attachment-name {
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            max-width: 200px;
            color: #333;
          }

          .attachment-size {
            color: #888;
            white-space: nowrap;
          }
        }

        .remove-attachment {
          background: none;
          border: none;
          cursor: pointer;
          padding: 2px;
          color: #666;

          mat-icon {
            font-size: 18px;
            width: 18px;
            height: 18px;
          }

          &:hover {
            color: #d32f2f;
          }
        }
      }

      .input-wrapper {
        display: flex;
        align-items: flex-end;
        gap: 12px;
        background-color: #f5f5f5;
        border-radius: 24px;
        padding: 8px 8px 8px 18px;
        transition:
          background-color 0.15s ease,
          box-shadow 0.15s ease;

        &:focus-within {
          background-color: #ffffff;
          box-shadow: 0 0 0 2px #e3f2fd;
        }
      }

      .attach-button {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 36px;
        height: 36px;
        border: none;
        border-radius: 50%;
        background: transparent;
        color: #666;
        cursor: pointer;
        flex-shrink: 0;
        transition:
          color 0.15s ease,
          background-color 0.15s ease;

        &:hover:not(:disabled) {
          color: #1976d2;
          background-color: rgba(25, 118, 210, 0.08);
        }

        &:disabled {
          color: #ccc;
          cursor: not-allowed;
        }

        mat-icon {
          font-size: 22px;
          width: 22px;
          height: 22px;
        }
      }

      .message-textarea {
        flex: 1;
        min-height: 24px;
        max-height: 120px;
        padding: 6px 0;
        border: none;
        background: transparent;
        font-size: 15px;
        font-family: inherit;
        line-height: 1.5;
        resize: none;
        outline: none;
        color: #1a1a1a;

        &::placeholder {
          color: #888;
        }

        &:disabled {
          color: #999;
        }
      }

      .char-counter {
        font-size: 12px;
        color: #888;
        white-space: nowrap;
        padding-bottom: 8px;

        &.warning {
          color: #f57c00;
        }

        &.danger {
          color: #d32f2f;
          font-weight: 600;
        }
      }

      .send-button {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 40px;
        height: 40px;
        border: none;
        border-radius: 50%;
        background: linear-gradient(135deg, #2196f3 0%, #1976d2 100%);
        color: #ffffff;
        cursor: pointer;
        transition:
          transform 0.15s ease,
          box-shadow 0.15s ease;
        flex-shrink: 0;

        &:hover:not(:disabled) {
          transform: scale(1.05);
          box-shadow: 0 2px 8px rgba(33, 150, 243, 0.3);
        }

        &:active:not(:disabled) {
          transform: scale(0.95);
        }

        &:disabled {
          background: #e0e0e0;
          color: #999;
          cursor: not-allowed;
        }

        mat-icon {
          font-size: 20px;
          width: 20px;
          height: 20px;
        }
      }

      // Dark theme
      :host-context(.dark-theme) {
        .input-container {
          background-color: #1e1e1e;
          border-top-color: #333;
        }

        .input-wrapper {
          background-color: #2d2d2d;

          &:focus-within {
            background-color: #333;
            box-shadow: 0 0 0 2px #0d47a1;
          }
        }

        .message-textarea {
          color: #e0e0e0;

          &::placeholder {
            color: #666;
          }
        }

        .send-button:disabled {
          background: #444;
          color: #666;
        }
      }

      // Responsive
      @media (max-width: 768px) {
        .input-container {
          padding: 12px 16px;
        }
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageInputComponent implements AfterViewInit, OnDestroy {
  @ViewChild('textareaRef') textareaRef!: ElementRef<HTMLTextAreaElement>;

  // Inputs
  disabled = input<boolean>(false);
  placeholder = input<string>('Napišite poruku...');
  isSending = input<boolean>(false);

  // Outputs
  messageSent = output<string>();
  attachmentSelected = output<File>();
  attachmentRemoved = output<void>();
  attachmentValidationError = output<string>();
  typingStarted = output<void>();
  typingStopped = output<void>();

  // Internal state
  inputValue = signal('');
  selectedFile = signal<File | null>(null);
  maxLength = 1000;
  private readonly MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
  protected readonly ALLOWED_TYPES = [
    'image/jpeg',
    'image/png',
    'image/gif',
    'image/webp',
    'application/pdf',
  ];
  private isTypingActive = false;
  private typingSubject = new Subject<string>();
  private destroy$ = new Subject<void>();

  // Computed
  canSend = computed(() => {
    return this.inputValue().trim().length > 0 && !this.disabled() && !this.isSending();
  });

  showCharacterCounter = computed(() => {
    return this.inputValue().length > this.maxLength * 0.8;
  });

  isNearLimit = () => {
    return (
      this.inputValue().length > this.maxLength * 0.9 && this.inputValue().length < this.maxLength
    );
  };

  isAtLimit = () => {
    return this.inputValue().length >= this.maxLength;
  };

  constructor() {
    // Debounced typing indicator
    this.typingSubject
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe((value) => {
        if (value.length > 0 && !this.isTypingActive) {
          this.isTypingActive = true;
          this.typingStarted.emit();
        } else if (value.length === 0 && this.isTypingActive) {
          this.isTypingActive = false;
          this.typingStopped.emit();
        }
      });
  }

  ngAfterViewInit(): void {
    this.adjustHeight();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onInput(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    this.inputValue.set(value);
    this.adjustHeight();
    this.typingSubject.next(value);
  }

  onEnterPress(event: KeyboardEvent): void {
    // Send on Enter without Shift
    if (!event.shiftKey) {
      event.preventDefault();
      this.onSend();
    }
  }

  onSend(): void {
    const content = this.inputValue().trim();
    if (content && !this.disabled() && !this.isSending()) {
      this.messageSent.emit(content);
      this.inputValue.set('');
      this.selectedFile.set(null); // Clear attachment preview after send
      this.adjustHeight();

      // Stop typing indicator
      if (this.isTypingActive) {
        this.isTypingActive = false;
        this.typingStopped.emit();
      }
    }
  }

  onFocus(): void {
    // Could trigger additional behavior
  }

  onBlur(): void {
    // Stop typing indicator on blur
    if (this.isTypingActive) {
      this.isTypingActive = false;
      this.typingStopped.emit();
    }
  }

  private adjustHeight(): void {
    const textarea = this.textareaRef?.nativeElement;
    if (textarea) {
      textarea.style.height = 'auto';
      textarea.style.height = Math.min(textarea.scrollHeight, 120) + 'px';
    }
  }

  // Public method for external focus
  focus(): void {
    this.textareaRef?.nativeElement?.focus();
  }

  // Public method to set value (for quick replies)
  setValue(value: string): void {
    this.inputValue.set(value);
    setTimeout(() => {
      this.adjustHeight();
      this.focus();
    }, 0);
  }

  // =========================================================================
  // File Attachment Handling
  // =========================================================================

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    // Validate file type
    if (!this.ALLOWED_TYPES.includes(file.type)) {
      this.attachmentValidationError.emit('Dozvoljeni formati su JPG, PNG, GIF, WEBP ili PDF.');
      // Reset input
      input.value = '';
      return;
    }

    // Validate file size
    if (file.size > this.MAX_FILE_SIZE) {
      this.attachmentValidationError.emit('Fajl je prevelik. Maksimalna veličina je 10MB.');
      // Reset input
      input.value = '';
      return;
    }

    this.selectedFile.set(file);
    this.attachmentSelected.emit(file);

    // Reset the input so the same file can be re-selected
    input.value = '';
  }

  removeAttachment(): void {
    this.selectedFile.set(null);
    this.attachmentRemoved.emit();
  }

  getAttachmentIcon(): string {
    const file = this.selectedFile();
    if (!file) return 'attach_file';
    if (file.type === 'application/pdf') return 'picture_as_pdf';
    if (file.type.startsWith('image/')) return 'image';
    return 'attach_file';
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
}
