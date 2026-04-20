import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';

/** Enterprise-grade shared button – Turo/Airbnb standard. Zero Angular Material deps. */
@Component({
  selector: 'app-rtz-button',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './button.component.html',
  styleUrls: ['./button.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ButtonComponent {
  /** Visual variant */
  @Input() variant: 'primary' | 'secondary' | 'ghost' | 'danger' | 'icon' = 'primary';

  /** Size preset */
  @Input() size: 'sm' | 'md' | 'lg' = 'md';

  /** Show loading spinner and block clicks */
  @Input() loading = false;

  /** Disable button and block clicks */
  @Input() disabled = false;

  /** Stretch to 100% container width */
  @Input() fullWidth = false;

  /** Native button type (submit / reset / button) */
  @Input() type: 'button' | 'submit' | 'reset' = 'button';

  /** Required for icon-only variant for accessibility */
  @Input() ariaLabel = '';

  @Output() clicked = new EventEmitter<MouseEvent>();

  /** Computed class list for host `<button>` */
  get classes(): Record<string, boolean> {
    return {
      [`btn--${this.variant}`]: true,
      [`btn--${this.size}`]: true,
      'btn--full-width': this.fullWidth,
      'btn--loading': this.loading,
      'btn--disabled': this.disabled || this.loading,
    };
  }

  handleClick(event: MouseEvent): void {
    if (this.loading || this.disabled) {
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    this.clicked.emit(event);
  }
}