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
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <div class="input-container" [class.disabled]="disabled()">
      <div class="input-wrapper">
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
  styles: [`
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

    .input-wrapper {
      display: flex;
      align-items: flex-end;
      gap: 12px;
      background-color: #f5f5f5;
      border-radius: 24px;
      padding: 8px 8px 8px 18px;
      transition: background-color 0.15s ease, box-shadow 0.15s ease;

      &:focus-within {
        background-color: #ffffff;
        box-shadow: 0 0 0 2px #e3f2fd;
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
      transition: transform 0.15s ease, box-shadow 0.15s ease;
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
  `],
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
  typingStarted = output<void>();
  typingStopped = output<void>();

  // Internal state
  inputValue = signal('');
  maxLength = 1000;
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
    return this.inputValue().length > this.maxLength * 0.9 && this.inputValue().length < this.maxLength;
  };

  isAtLimit = () => {
    return this.inputValue().length >= this.maxLength;
  };

  constructor() {
    // Debounced typing indicator
    this.typingSubject
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        takeUntil(this.destroy$)
      )
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
}
