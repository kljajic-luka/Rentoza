import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  Input,
  OnInit,
  Optional,
  Self,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ControlValueAccessor,
  FormControl,
  NgControl,
  ReactiveFormsModule,
} from '@angular/forms';

/**
 * Enterprise-grade form input component.
 * Implements ControlValueAccessor so it works seamlessly with
 * template-driven (ngModel) and reactive (FormControl) forms.
 *
 * Usage:
 *   <app-rtz-input label="Email" type="email" [formControl]="emailControl" />
 *   <app-rtz-input label="Lozinka" type="password" clearable />
 */
@Component({
  selector: 'app-rtz-input',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './form-input.component.html',
  styleUrls: ['./form-input.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormInputComponent implements ControlValueAccessor, OnInit {
  private readonly cdr = inject(ChangeDetectorRef);

  @ViewChild('inputEl') inputEl!: ElementRef<HTMLInputElement | HTMLTextAreaElement>;

  // ── Inputs ─────────────────────────────────────────────────────────────
  @Input({ required: true }) label = '';
  @Input() type = 'text';
  /** Space is intentional — CSS floating label trick requires non-empty placeholder */
  @Input() placeholder = ' ';
  @Input() hint = '';
  @Input() maxlength: number | null = null;
  /** Material icon name for left side (e.g. "email", "search") */
  @Input() leftIcon = '';
  /** Material icon name for right side */
  @Input() rightIcon = '';
  @Input() clearable = false;
  @Input() disabled = false;
  @Input() required = false;
  /** Used for textarea variant */
  @Input() rows = 4;
  /** Auto id for linking label ↔ input */
  readonly inputId = `rtz-input-${Math.random().toString(36).slice(2, 8)}`;

  // ── Internal state (signals) ────────────────────────────────────────────
  readonly isFocused = signal(false);
  readonly showPassword = signal(false);
  readonly innerValue = signal<string>('');

  // Computed helpers
  readonly hasValue = computed(() => this.innerValue().length > 0);
  readonly isPasswordType = computed(() => this.type === 'password');
  readonly effectiveType = computed(() => {
    if (this.isPasswordType()) {
      return this.showPassword() ? 'text' : 'password';
    }
    return this.type;
  });
  readonly charCount = computed(() => this.innerValue().length);
  readonly isLabelFloating = computed(() => this.isFocused() || this.hasValue());

  // ── CVA internals ───────────────────────────────────────────────────────
  private onChange: (value: string) => void = () => {};
  private onTouched: () => void = () => {};

  /** Access parent FormControl for real-time validation state */
  constructor(@Optional() @Self() public readonly ngControl: NgControl) {
    if (ngControl) {
      ngControl.valueAccessor = this;
    }
  }

  ngOnInit(): void {}

  // ── ControlValueAccessor impl ────────────────────────────────────────────
  writeValue(value: string | null | undefined): void {
    this.innerValue.set(value ?? '');
    this.cdr.markForCheck();
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    this.cdr.markForCheck();
  }

  // ── Event handlers ───────────────────────────────────────────────────────
  onInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.innerValue.set(value);
    this.onChange(value);
    this.cdr.markForCheck();
  }

  onFocus(): void {
    this.isFocused.set(true);
    this.cdr.markForCheck();
  }

  onBlur(): void {
    this.isFocused.set(false);
    this.onTouched();
    this.cdr.markForCheck();
  }

  clearValue(): void {
    this.innerValue.set('');
    this.onChange('');
    this.onTouched();
    this.inputEl?.nativeElement?.focus();
    this.cdr.markForCheck();
  }

  togglePasswordVisibility(): void {
    this.showPassword.update((v) => !v);
    this.cdr.markForCheck();
  }

  // ── Validation helpers ───────────────────────────────────────────────────
  get parentControl(): FormControl | null {
    return (this.ngControl?.control as FormControl) ?? null;
  }

  get isInvalid(): boolean {
    const ctrl = this.parentControl;
    return !!(ctrl && ctrl.invalid && (ctrl.dirty || ctrl.touched));
  }

  get isValid(): boolean {
    const ctrl = this.parentControl;
    return !!(ctrl && ctrl.valid && (ctrl.dirty || ctrl.touched) && this.hasValue());
  }

  get errorMessage(): string {
    const ctrl = this.parentControl;
    if (!ctrl || !ctrl.errors) return '';
    const errs = ctrl.errors;

    if (errs['required']) return 'Ovo polje je obavezno';
    if (errs['email']) return 'Unesite ispravnu email adresu';
    if (errs['minlength']) {
      const req = errs['minlength'].requiredLength;
      return `Minimalno ${req} karaktera`;
    }
    if (errs['maxlength']) {
      const req = errs['maxlength'].requiredLength;
      return `Maksimalno ${req} karaktera`;
    }
    if (errs['pattern']) return 'Neispravan format';
    // Fallback to first error key
    return `Greška: ${Object.keys(errs)[0]}`;
  }
}
