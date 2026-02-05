import { Component, ChangeDetectionStrategy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/**
 * Data Privacy Component
 *
 * GDPR-compliant self-service for users to:
 * - Export their personal data
 * - Delete their account
 * - View consent preferences
 */
@Component({
  selector: 'app-data-privacy',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatSnackBarModule,
  ],
  template: `
    <div class="privacy-container">
      <h1>Privatnost podataka</h1>
      <p class="subtitle">Upravljajte svojim podacima u skladu sa GDPR regulativom</p>

      <!-- Data Export Section -->
      <mat-card class="privacy-card">
        <mat-card-header>
          <mat-icon mat-card-avatar>download</mat-icon>
          <mat-card-title>Izvoz podataka</mat-card-title>
          <mat-card-subtitle> Preuzmite kopiju svih vaših podataka </mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <p>Možete zatražiti kopiju svih podataka koje čuvamo o vama. Ovo uključuje:</p>
          <ul>
            <li>Profilne informacije (ime, email, telefon)</li>
            <li>Istorija rezervacija</li>
            <li>Recenzije koje ste dali i primili</li>
            <li>Oglasi za vozila (ako ste vlasnik)</li>
            <li>Istorija saglasnosti</li>
          </ul>
          <p class="note">
            <mat-icon>info</mat-icon>
            Izvoz je ograničen na jednom u 24 sata.
          </p>
        </mat-card-content>

        <mat-card-actions>
          <button mat-raised-button color="primary" (click)="exportData()" [disabled]="exporting()">
            @if (exporting()) {
              <mat-spinner diameter="20"></mat-spinner>
              Izvoz u toku...
            } @else {
              <mat-icon>cloud_download</mat-icon>
              Preuzmi moje podatke
            }
          </button>
        </mat-card-actions>
      </mat-card>

      <mat-divider></mat-divider>

      <!-- Consent Preferences Section -->
      <mat-card class="privacy-card">
        <mat-card-header>
          <mat-icon mat-card-avatar>tune</mat-icon>
          <mat-card-title>Postavke saglasnosti</mat-card-title>
          <mat-card-subtitle> Upravljajte kako koristimo vaše podatke </mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <p>
            Kontrolišite koje vrste komunikacije primate i kako koristimo vaše podatke za analitiku.
          </p>
        </mat-card-content>

        <mat-card-actions>
          <button mat-stroked-button color="primary" routerLink="/profile/notifications">
            <mat-icon>notifications</mat-icon>
            Podešavanja obaveštenja
          </button>
        </mat-card-actions>
      </mat-card>

      <mat-divider></mat-divider>

      <!-- Account Deletion Section -->
      <mat-card class="privacy-card danger-zone">
        <mat-card-header>
          <mat-icon mat-card-avatar class="danger-icon">delete_forever</mat-icon>
          <mat-card-title>Brisanje naloga</mat-card-title>
          <mat-card-subtitle> Trajno obrišite vaš nalog i sve podatke </mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <div class="warning-box">
            <mat-icon>warning</mat-icon>
            <p>
              <strong>Ovo je nepovratna akcija.</strong>
              Vaš nalog i svi povezani podaci biće trajno obrisani nakon 30-dnevnog perioda za
              predomišljanje.
            </p>
          </div>

          <p>Šta se dešava kada obrišete nalog:</p>
          <ul>
            <li>Nalog se odmah deaktivira</li>
            <li>Imate 30 dana da otkažete brisanje</li>
            <li>Nakon 30 dana, podaci se anonimizuju</li>
            <li>Finansijski podaci se čuvaju 7 godina (zakonska obaveza)</li>
          </ul>

          <p class="note">
            <mat-icon>error_outline</mat-icon>
            Ne možete obrisati nalog ako imate aktivne rezervacije.
          </p>
        </mat-card-content>

        <mat-card-actions>
          <button
            mat-raised-button
            color="warn"
            (click)="confirmDeleteAccount()"
            [disabled]="deleting()"
          >
            @if (deleting()) {
              <mat-spinner diameter="20"></mat-spinner>
              Obrada...
            } @else {
              <mat-icon>delete</mat-icon>
              Obriši moj nalog
            }
          </button>
        </mat-card-actions>
      </mat-card>

      <!-- Legal Links -->
      <div class="legal-links">
        <a routerLink="/legal/privacy-policy">
          <mat-icon>privacy_tip</mat-icon>
          Politika privatnosti
        </a>
        <a routerLink="/legal/terms-of-service">
          <mat-icon>gavel</mat-icon>
          Uslovi korišćenja
        </a>
      </div>
    </div>
  `,
  styles: [
    `
      .privacy-container {
        max-width: 800px;
        margin: 24px auto;
        padding: 0 16px;

        h1 {
          font-size: 2rem;
          margin-bottom: 8px;
        }

        .subtitle {
          color: #666;
          margin-bottom: 32px;
        }
      }

      .privacy-card {
        margin-bottom: 24px;

        mat-card-header {
          mat-icon[mat-card-avatar] {
            background: #f5f5f5;
            border-radius: 50%;
            padding: 8px;
            font-size: 24px;
            width: 40px;
            height: 40px;
          }
        }

        mat-card-content {
          p {
            color: #666;
            line-height: 1.6;
          }

          ul {
            color: #666;
            line-height: 1.8;
            padding-left: 24px;
            margin: 16px 0;
          }

          .note {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 12px;
            background: #f5f5f5;
            border-radius: 8px;
            font-size: 14px;

            mat-icon {
              color: #666;
            }
          }
        }

        mat-card-actions {
          padding: 16px;

          button {
            display: flex;
            align-items: center;
            gap: 8px;
          }
        }

        &.danger-zone {
          border: 1px solid #f44336;

          mat-icon.danger-icon {
            color: #f44336;
          }

          .warning-box {
            display: flex;
            gap: 12px;
            padding: 16px;
            background: #fff3e0;
            border-radius: 8px;
            margin-bottom: 16px;

            mat-icon {
              color: #ff9800;
            }

            p {
              margin: 0;
            }
          }
        }
      }

      mat-divider {
        margin: 24px 0;
      }

      .legal-links {
        display: flex;
        gap: 24px;
        justify-content: center;
        padding: 24px 0;

        a {
          display: flex;
          align-items: center;
          gap: 8px;
          color: var(--primary-color, #3f51b5);
          text-decoration: none;

          &:hover {
            text-decoration: underline;
          }
        }
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DataPrivacyComponent {
  private http = inject(HttpClient);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  readonly exporting = signal(false);
  readonly deleting = signal(false);

  async exportData(): Promise<void> {
    this.exporting.set(true);

    try {
      const data = await firstValueFrom(this.http.get<any>('/api/users/me/data-export'));

      // Download as JSON file
      const blob = new Blob([JSON.stringify(data, null, 2)], {
        type: 'application/json',
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `rentoza-export-${new Date().toISOString().split('T')[0]}.json`;
      a.click();
      URL.revokeObjectURL(url);

      this.snackBar.open('Podaci su preuzeti!', 'OK', { duration: 3000 });
    } catch (error: any) {
      if (error.status === 429) {
        this.snackBar.open(
          'Izvoz je ograničen na jednom u 24 sata. Pokušajte ponovo kasnije.',
          'OK',
          { duration: 5000 },
        );
      } else {
        this.snackBar.open('Greška pri izvozu podataka', 'OK', { duration: 3000 });
      }
    } finally {
      this.exporting.set(false);
    }
  }

  async confirmDeleteAccount(): Promise<void> {
    const confirmed = window.confirm(
      'Da li ste sigurni da želite da obrišete vaš nalog?\n\n' +
        'Ova akcija će deaktivirati vaš nalog odmah.\n' +
        'Imate 30 dana da otkažete brisanje.',
    );

    if (!confirmed) return;

    const email = window.prompt('Unesite vašu email adresu za potvrdu:');

    if (!email) return;

    this.deleting.set(true);

    try {
      await firstValueFrom(
        this.http.delete<any>('/api/users/me/delete', {
          body: {
            confirmEmail: email,
            reason: 'User requested account deletion',
          },
        }),
      );

      this.snackBar.open('Nalog je zakazan za brisanje. Imate 30 dana da otkažete.', 'OK', {
        duration: 5000,
      });

      // Redirect to logout
      window.location.href = '/auth/logout';
    } catch (error: any) {
      if (error.status === 400) {
        this.snackBar.open('Ne možete obrisati nalog dok imate aktivne rezervacije.', 'OK', {
          duration: 5000,
        });
      } else if (error.status === 409) {
        this.snackBar.open('Email adresa se ne poklapa sa vašim nalogom.', 'OK', {
          duration: 3000,
        });
      } else {
        this.snackBar.open('Greška pri brisanju naloga', 'OK', { duration: 3000 });
      }
    } finally {
      this.deleting.set(false);
    }
  }
}
