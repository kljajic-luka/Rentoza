import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '@environments/environment';

export type OwnerType = 'INDIVIDUAL' | 'LEGAL_ENTITY';

export interface OwnerVerificationStatus {
  ownerType: OwnerType | null;
  maskedId: string | null;
  isVerified: boolean;
  status: 'NOT_SUBMITTED' | 'PENDING_REVIEW' | 'VERIFIED';
  verifiedAt: string | null;
}

export interface IndividualVerificationRequest {
  jmbg: string;
  bankAccountNumber?: string;
}

export interface LegalEntityVerificationRequest {
  pib: string;
  bankAccountNumber: string;
}

@Injectable({
  providedIn: 'root',
})
export class OwnerVerificationService {
  private apiUrl = `${environment.baseApiUrl}/users/me/owner-verification`;

  constructor(private http: HttpClient) {}

  /**
   * Get current verification status.
   */
  getStatus(): Observable<OwnerVerificationStatus> {
    return this.http.get<OwnerVerificationStatus>(this.apiUrl);
  }

  /**
   * Submit individual verification (JMBG).
   */
  submitIndividual(request: IndividualVerificationRequest): Observable<OwnerVerificationStatus> {
    return this.http.post<OwnerVerificationStatus>(`${this.apiUrl}/individual`, request);
  }

  /**
   * Submit legal entity verification (PIB).
   */
  submitLegalEntity(request: LegalEntityVerificationRequest): Observable<OwnerVerificationStatus> {
    return this.http.post<OwnerVerificationStatus>(`${this.apiUrl}/legal-entity`, request);
  }
}