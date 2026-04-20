import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '@environments/environment';

export type DocumentType =
  | 'REGISTRATION'
  | 'TECHNICAL_INSPECTION'
  | 'LIABILITY_INSURANCE'
  | 'AUTHORIZATION';
export type DocumentStatus = 'PENDING' | 'VERIFIED' | 'REJECTED' | 'EXPIRED_AUTO';

export interface CarDocument {
  id: number;
  type: DocumentType;
  typeSerbianName: string;
  originalFilename: string;
  fileSize: number;
  mimeType: string;
  uploadDate: string;
  expiryDate: string;
  status: DocumentStatus;
  rejectionReason?: string;
  isExpired: boolean;
  daysUntilExpiry: number;
  verifiedById?: number;
  verifiedAt?: string;
}

export interface DocumentComplianceStatus {
  allRequiredVerified: boolean;
  registrationVerified: boolean;
  technicalInspectionVerified: boolean;
  insuranceVerified: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class CarDocumentService {
  private apiUrl = `${environment.baseApiUrl}/cars`;

  constructor(private http: HttpClient) {}

  /**
   * Upload document with progress tracking.
   */
  uploadDocument(
    carId: number,
    file: File,
    type: DocumentType,
    expiryDate?: string,
  ): Observable<HttpEvent<CarDocument>> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('type', type);
    if (expiryDate) {
      formData.append('expiryDate', expiryDate);
    }

    const req = new HttpRequest('POST', `${this.apiUrl}/${carId}/documents`, formData, {
      reportProgress: true,
    });

    return this.http.request<CarDocument>(req);
  }

  /**
   * Get all documents for a car.
   */
  getDocuments(carId: number): Observable<CarDocument[]> {
    return this.http.get<CarDocument[]>(`${this.apiUrl}/${carId}/documents`);
  }

  /**
   * Get compliance status for a car.
   */
  getComplianceStatus(carId: number): Observable<DocumentComplianceStatus> {
    return this.http.get<DocumentComplianceStatus>(`${this.apiUrl}/${carId}/documents/status`);
  }
}