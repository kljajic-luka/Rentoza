/**
 * Maps backend payment lifecycle statuses to user-friendly Serbian labels.
 *
 * Covers ChargeLifecycleStatus, DepositLifecycleStatus, and PayoutLifecycleStatus
 * from the backend payment state machines.
 */

export type ChargeLifecycleStatus =
  | 'PENDING'
  | 'AUTHORIZED'
  | 'REAUTH_REQUIRED'
  | 'CAPTURED'
  | 'RELEASED'
  | 'REFUNDED'
  | 'CAPTURE_FAILED'
  | 'RELEASE_FAILED'
  | 'MANUAL_REVIEW';

export type DepositLifecycleStatus =
  | 'PENDING'
  | 'AUTHORIZED'
  | 'RELEASED'
  | 'PARTIAL_CAPTURED'
  | 'CAPTURED'
  | 'EXPIRED'
  | 'MANUAL_REVIEW';

export type PayoutLifecycleStatus =
  | 'PENDING'
  | 'ELIGIBLE'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'ON_HOLD'
  | 'FAILED'
  | 'MANUAL_REVIEW';

// ─── Charge Status Labels ────────────────────────────────────────────

const CHARGE_STATUS_LABELS: Record<ChargeLifecycleStatus, string> = {
  PENDING: 'Na cekanju',
  AUTHORIZED: 'Autorizovano',
  REAUTH_REQUIRED: 'Potrebna ponovna autorizacija',
  CAPTURED: 'Naplaceno',
  RELEASED: 'Otpusteno',
  REFUNDED: 'Refundirano',
  CAPTURE_FAILED: 'Naplata neuspesna',
  RELEASE_FAILED: 'Otpustanje neuspesno',
  MANUAL_REVIEW: 'Na pregledu',
};

const CHARGE_STATUS_ICONS: Record<ChargeLifecycleStatus, string> = {
  PENDING: 'hourglass_empty',
  AUTHORIZED: 'lock',
  REAUTH_REQUIRED: 'warning',
  CAPTURED: 'check_circle',
  RELEASED: 'lock_open',
  REFUNDED: 'replay',
  CAPTURE_FAILED: 'error',
  RELEASE_FAILED: 'error',
  MANUAL_REVIEW: 'support_agent',
};

const CHARGE_STATUS_COLORS: Record<ChargeLifecycleStatus, string> = {
  PENDING: 'neutral',
  AUTHORIZED: 'info',
  REAUTH_REQUIRED: 'warn',
  CAPTURED: 'success',
  RELEASED: 'neutral',
  REFUNDED: 'info',
  CAPTURE_FAILED: 'error',
  RELEASE_FAILED: 'error',
  MANUAL_REVIEW: 'warn',
};

// ─── Deposit Status Labels ───────────────────────────────────────────

const DEPOSIT_STATUS_LABELS: Record<DepositLifecycleStatus, string> = {
  PENDING: 'Na cekanju',
  AUTHORIZED: 'Zadrzano na kartici',
  RELEASED: 'Vraceno',
  PARTIAL_CAPTURED: 'Delimicno naplaceno',
  CAPTURED: 'Naplaceno',
  EXPIRED: 'Isteklo',
  MANUAL_REVIEW: 'Na pregledu',
};

const DEPOSIT_STATUS_ICONS: Record<DepositLifecycleStatus, string> = {
  PENDING: 'hourglass_empty',
  AUTHORIZED: 'lock',
  RELEASED: 'check_circle',
  PARTIAL_CAPTURED: 'warning',
  CAPTURED: 'money_off',
  EXPIRED: 'timer_off',
  MANUAL_REVIEW: 'support_agent',
};

const DEPOSIT_STATUS_COLORS: Record<DepositLifecycleStatus, string> = {
  PENDING: 'neutral',
  AUTHORIZED: 'info',
  RELEASED: 'success',
  PARTIAL_CAPTURED: 'warn',
  CAPTURED: 'error',
  EXPIRED: 'error',
  MANUAL_REVIEW: 'warn',
};

// ─── Payout Status Labels ────────────────────────────────────────────

const PAYOUT_STATUS_LABELS: Record<PayoutLifecycleStatus, string> = {
  PENDING: 'Na cekanju',
  ELIGIBLE: 'Spremno za isplatu',
  PROCESSING: 'U obradi',
  COMPLETED: 'Isplaceno',
  ON_HOLD: 'Zadrzano',
  FAILED: 'Neuspesno',
  MANUAL_REVIEW: 'Na pregledu',
};

const PAYOUT_STATUS_ICONS: Record<PayoutLifecycleStatus, string> = {
  PENDING: 'hourglass_empty',
  ELIGIBLE: 'schedule',
  PROCESSING: 'sync',
  COMPLETED: 'check_circle',
  ON_HOLD: 'pause_circle',
  FAILED: 'error',
  MANUAL_REVIEW: 'support_agent',
};

const PAYOUT_STATUS_COLORS: Record<PayoutLifecycleStatus, string> = {
  PENDING: 'neutral',
  ELIGIBLE: 'info',
  PROCESSING: 'info',
  COMPLETED: 'success',
  ON_HOLD: 'warn',
  FAILED: 'error',
  MANUAL_REVIEW: 'warn',
};

// ─── Guest-Facing Payment Summary ────────────────────────────────────

/**
 * Returns a simplified, guest-friendly description of the payment state.
 * Used in the booking detail view for renters.
 */
export function getGuestPaymentSummary(chargeStatus: ChargeLifecycleStatus): string {
  switch (chargeStatus) {
    case 'PENDING':
      return 'Placanje na cekanju';
    case 'AUTHORIZED':
      return 'Sredstva zadrzana na kartici';
    case 'REAUTH_REQUIRED':
      return 'Potrebna ponovna verifikacija kartice';
    case 'CAPTURED':
      return 'Placanje zavrseno';
    case 'RELEASED':
      return 'Sredstva oslobodjena';
    case 'REFUNDED':
      return 'Refundacija izvrsena';
    case 'CAPTURE_FAILED':
      return 'Problem sa naplatom — kontaktirajte podrsku';
    case 'RELEASE_FAILED':
      return 'Problem sa vracanjem sredstava — kontaktirajte podrsku';
    case 'MANUAL_REVIEW':
      return 'Placanje na pregledu — kontaktirajte podrsku';
    default:
      return 'Nepoznat status';
  }
}

/**
 * Returns a guest-friendly description of the deposit state.
 */
export function getGuestDepositSummary(
  depositStatus: DepositLifecycleStatus,
  depositAmount?: number,
): string {
  const formattedAmount = depositAmount
    ? `${depositAmount.toLocaleString('sr-RS')} RSD`
    : 'Depozit';

  switch (depositStatus) {
    case 'PENDING':
      return `${formattedAmount} — na cekanju`;
    case 'AUTHORIZED':
      return `${formattedAmount} — zadrzano na kartici`;
    case 'RELEASED':
      return `${formattedAmount} — vraceno u celosti`;
    case 'PARTIAL_CAPTURED':
      return 'Deo depozita zadrzan zbog stete';
    case 'CAPTURED':
      return `${formattedAmount} — naplaceno`;
    case 'EXPIRED':
      return 'Autorizacija depozita istekla';
    case 'MANUAL_REVIEW':
      return 'Depozit na pregledu — kontaktirajte podrsku';
    default:
      return 'Nepoznat status depozita';
  }
}

// ─── Public Mappers ──────────────────────────────────────────────────

export function getChargeStatusLabel(status: ChargeLifecycleStatus): string {
  return CHARGE_STATUS_LABELS[status] ?? status;
}

export function getChargeStatusIcon(status: ChargeLifecycleStatus): string {
  return CHARGE_STATUS_ICONS[status] ?? 'help_outline';
}

export function getChargeStatusColor(status: ChargeLifecycleStatus): string {
  return CHARGE_STATUS_COLORS[status] ?? 'neutral';
}

export function getDepositStatusLabel(status: DepositLifecycleStatus): string {
  return DEPOSIT_STATUS_LABELS[status] ?? status;
}

export function getDepositStatusIcon(status: DepositLifecycleStatus): string {
  return DEPOSIT_STATUS_ICONS[status] ?? 'help_outline';
}

export function getDepositStatusColor(status: DepositLifecycleStatus): string {
  return DEPOSIT_STATUS_COLORS[status] ?? 'neutral';
}

export function getPayoutStatusLabel(status: PayoutLifecycleStatus): string {
  return PAYOUT_STATUS_LABELS[status] ?? status;
}

export function getPayoutStatusIcon(status: PayoutLifecycleStatus): string {
  return PAYOUT_STATUS_ICONS[status] ?? 'help_outline';
}

export function getPayoutStatusColor(status: PayoutLifecycleStatus): string {
  return PAYOUT_STATUS_COLORS[status] ?? 'neutral';
}

/**
 * Whether the given charge status requires guest attention (action or awareness).
 */
export function isChargeStatusActionRequired(status: ChargeLifecycleStatus): boolean {
  return status === 'REAUTH_REQUIRED' || status === 'CAPTURE_FAILED';
}
