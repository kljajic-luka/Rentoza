import { AgreementSummary } from '@core/models/booking.model';

type AgreementLike = {
  status?: string | null;
  enforcementEnabled?: boolean | null;
};

export function canProceedToCheckInFromSummary(
  summary: AgreementSummary | null | undefined,
): boolean {
  if (!summary) {
    return true;
  }

  return summary.legacyBooking || summary.currentActorCanProceedToCheckIn;
}

export function shouldShowAgreementPrimaryActionFromSummary(
  summary: AgreementSummary | null | undefined,
): boolean {
  return !!summary
    && !summary.legacyBooking
    && !summary.currentActorCanProceedToCheckIn
    && summary.recommendedPrimaryAction !== 'OPEN_CHECK_IN';
}

export function isAgreementEnforcementEnabled(
  enforcementEnabled: boolean | null | undefined,
): boolean {
  return enforcementEnabled !== false;
}

export function canProceedToCheckInFromAgreement(
  agreement: AgreementLike | null | undefined,
): boolean {
  if (!agreement) {
    return false;
  }

  return !isAgreementEnforcementEnabled(agreement.enforcementEnabled)
    || agreement.status === 'FULLY_ACCEPTED';
}