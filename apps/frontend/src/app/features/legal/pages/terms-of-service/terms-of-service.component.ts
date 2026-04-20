import { Component, ChangeDetectionStrategy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';

/**
 * Terms of Service Component
 *
 * Comprehensive terms governing use of the Rentoza platform.
 * Covers rental agreements, liability, cancellation policy, etc.
 *
 * Supports Serbian (default) and English languages.
 */
@Component({
  selector: 'app-terms-of-service',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatDividerModule,
  ],
  template: `
    <div class="terms-container">
      <mat-card class="terms-card">
        <mat-card-header>
          <mat-card-title>
            @if (currentLang() === 'sr') {
              Uslovi korišćenja
            } @else {
              Terms of Service
            }
          </mat-card-title>
          <mat-card-subtitle>
            @if (currentLang() === 'sr') {
              Poslednja izmena: {{ lastUpdated }}
            } @else {
              Last updated: {{ lastUpdatedEn }}
            }
          </mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <div class="lang-toggle">
            <button mat-stroked-button (click)="toggleLanguage()">
              <mat-icon>translate</mat-icon>
              {{ currentLang() === 'sr' ? 'English' : 'Srpski' }}
            </button>
          </div>

          @if (currentLang() === 'sr') {
            <!-- Serbian Content -->
            <section>
              <h2>1. O platformi</h2>
              <p>
                Rentoza je P2P (peer-to-peer) platforma za iznajmljivanje vozila koja povezuje
                vlasnike automobila sa korisnicima koji žele da iznajme vozilo. Rentoza DOO, sa
                sedištem u Beogradu, Srbija, upravlja ovom platformom.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>2. Uslovi registracije</h2>
              <h3>2.1 Opšti uslovi</h3>
              <ul>
                <li>Morate imati najmanje 18 godina za registraciju</li>
                <li>Morate imati najmanje 21 godinu za iznajmljivanje vozila</li>
                <li>Morate pružiti tačne i potpune podatke</li>
              </ul>

              <h3>2.2 Verifikacija identiteta</h3>
              <p>
                Za iznajmljivanje vozila potrebna je verifikacija vozačke dozvole. Vozačka dozvola
                mora biti važeća tokom čitavog perioda iznajmljivanja.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>3. Pravila iznajmljivanja</h2>
              <h3>3.1 Rezervacije</h3>
              <ul>
                <li>Minimalni period iznajmljivanja: 1 dan</li>
                <li>Maksimalni period iznajmljivanja: 30 dana</li>
                <li>Rezervacija mora biti napravljena najmanje 2 sata unapred</li>
              </ul>

              <h3>3.2 Otkazivanje</h3>
              <p>Koristimo Turo-stil politiku otkazivanja:</p>
              <ul>
                <li><strong>Besplatno otkazivanje:</strong> Više od 24h pre početka</li>
                <li><strong>Period za predomišljanje:</strong> Unutar 1h od rezervacije</li>
                <li><strong>Kasno otkazivanje:</strong> Naplaćuje se penali (10-30%)</li>
              </ul>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>4. Odgovornost</h2>
              <h3>4.1 Odgovornost korisnika</h3>
              <ul>
                <li>Korisnik je odgovoran za svu štetu nastalu tokom perioda iznajmljivanja</li>
                <li>Korisnik mora prijaviti svaku štetu ili nezgodu u roku od 48 sati</li>
                <li>Zabranjeno je korišćenje vozila za ilegalne aktivnosti</li>
              </ul>

              <h3>4.2 Odgovornost vlasnika</h3>
              <ul>
                <li>Vlasnik garantuje da je vozilo tehnički ispravno</li>
                <li>Vlasnik mora imati važeću registraciju i osiguranje</li>
                <li>Vlasnik je odgovoran za tačnost opisa vozila</li>
              </ul>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>5. Plaćanja</h2>
              <ul>
                <li>Sva plaćanja se vrše putem platforme</li>
                <li>Platforma zadržava proviziju od 15% od svake transakcije</li>
                <li>
                  Isplate vlasnicima se vrše u roku od 3 radna dana nakon završetka iznajmljivanja
                </li>
              </ul>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>6. Rešavanje sporova</h2>
              <p>
                U slučaju spora, korisnici mogu podneti reklamaciju putem platforme. Rentoza će
                posredovati i doneti konačnu odluku na osnovu dostupnih dokaza.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>7. Kontakt</h2>
              <p>Za sva pitanja vezana za uslove korišćenja, kontaktirajte nas:</p>
              <p><strong>Email:</strong> {{ contactEmail }}</p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>8. Pravni status platforme</h2>
              <p>
                Rentoza DOO je pruzalac usluga informacionog drustva u smislu Zakona o
                elektronskoj trgovini (Sl. glasnik RS 41/2009). Rentoza NIJE strana u ugovoru
                o zakupu vozila — ugovor o zakupu se zakljucuje direktno izmedju vlasnika
                i zakupca.
              </p>
              <p>
                Rentoza pruza uslugu posredovanja u smislu cl. 813-826 Zakona o obligacionim
                odnosima: povezuje vlasnike i zakupce, olaksava komunikaciju, obradjuje
                placanja putem licenciranog platnog procesora, i posreduje u sporovima.
              </p>
              <p>
                Rentoza ne poseduje, ne upravlja, niti odrzava vozila koja se iznajmljuju
                putem platforme. Vlasnik je iskljucivo odgovoran za tehnicku ispravnost,
                registraciju i obavezno osiguranje od auto odgovornosti (AO polisa) svog
                vozila.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>9. Zastita Rentoza</h2>
              <p>
                Zastita Rentoza je ugovorna garancija platforme Rentoza DOO u skladu sa
                Zakonom o obligacionim odnosima. Ovo NIJE polisa osiguranja u smislu
                Zakona o osiguranju (Sl. glasnik RS 139/2014).
              </p>
              <h3>Nivoi zastite:</h3>
              <ul>
                <li>
                  <strong>BASIC (ukljucena):</strong> Depozit u punom iznosu. Stete se pokrivaju
                  iz depozita.
                </li>
                <li>
                  <strong>STANDARD (+10%):</strong> Smanjeni depozit (50%). Rentoza garantuje
                  pokrice do 150.000 RSD iznad depozita.
                </li>
                <li>
                  <strong>PREMIUM (+20%):</strong> Bez depozita. Rentoza garantuje pokrice do
                  300.000 RSD.
                </li>
              </ul>
              <p>
                Ova zastita pokriva materijalne stete na vozilu nastale tokom trajanja zakupa,
                dokumentovane foto-inspekcijom pri preuzimanju i vracanju vozila. Ne pokriva:
                namernu stetu, upotrebu za nezakonite aktivnosti, vodjenje vozila pod
                dejstvom alkohola ili opojnih sredstava, ili stete nastale van ugovorenog
                perioda zakupa.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>10. Pravo na odustajanje</h2>
              <p>
                U skladu sa cl. 29 tac. 12 Zakona o zastiti potrosaca, pravo na odustajanje
                od ugovora u roku od 14 dana NE primenjuje se na usluge sa ugovorenim datumom
                izvrsenja (rezervacije za odredjeni datum). Besplatno otkazivanje je moguce
                najkasnije 24 casa pre pocetka zakupa — videti politiku otkazivanja.
              </p>
            </section>
          } @else {
            <!-- English Content -->
            <section>
              <h2>1. About the Platform</h2>
              <p>
                Rentoza is a P2P (peer-to-peer) vehicle rental platform that connects car owners
                with users who want to rent a vehicle. Rentoza DOO, based in Belgrade, Serbia,
                operates this platform.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>2. Registration Requirements</h2>
              <h3>2.1 General Requirements</h3>
              <ul>
                <li>You must be at least 18 years old to register</li>
                <li>You must be at least 21 years old to rent a vehicle</li>
                <li>You must provide accurate and complete information</li>
              </ul>

              <h3>2.2 Identity Verification</h3>
              <p>
                Driver's license verification is required to rent vehicles. The license must be
                valid throughout the entire rental period.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>3. Rental Rules</h2>
              <h3>3.1 Reservations</h3>
              <ul>
                <li>Minimum rental period: 1 day</li>
                <li>Maximum rental period: 30 days</li>
                <li>Reservations must be made at least 2 hours in advance</li>
              </ul>

              <h3>3.2 Cancellation</h3>
              <p>We use a Turo-style cancellation policy:</p>
              <ul>
                <li><strong>Free cancellation:</strong> More than 24h before start</li>
                <li><strong>Remorse period:</strong> Within 1h of booking</li>
                <li><strong>Late cancellation:</strong> Penalties apply (10-30%)</li>
              </ul>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>4. Liability</h2>
              <h3>4.1 Renter Liability</h3>
              <ul>
                <li>The renter is responsible for all damage during the rental period</li>
                <li>The renter must report any damage or accident within 48 hours</li>
                <li>Using the vehicle for illegal activities is prohibited</li>
              </ul>

              <h3>4.2 Owner Liability</h3>
              <ul>
                <li>The owner guarantees that the vehicle is technically sound</li>
                <li>The owner must have valid registration and insurance</li>
                <li>The owner is responsible for the accuracy of the vehicle description</li>
              </ul>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>5. Payments</h2>
              <ul>
                <li>All payments are processed through the platform</li>
                <li>The platform retains a 15% commission on each transaction</li>
                <li>Payouts to owners are made within 3 business days after the rental ends</li>
              </ul>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>6. Dispute Resolution</h2>
              <p>
                In case of a dispute, users can file a complaint through the platform. Rentoza will
                mediate and make a final decision based on available evidence.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>7. Contact</h2>
              <p>For any questions regarding terms of service, contact us:</p>
              <p><strong>Email:</strong> {{ contactEmail }}</p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>8. Legal Status of the Platform</h2>
              <p>
                Rentoza DOO is an information society service provider within the meaning of the
                Law on Electronic Commerce (Official Gazette RS 41/2009). Rentoza is NOT a party
                to the vehicle rental agreement — the rental agreement is concluded directly
                between the owner and the renter.
              </p>
              <p>
                Rentoza provides intermediation services within the meaning of Articles 813-826
                of the Law on Obligations: connecting owners and renters, facilitating
                communication, processing payments through a licensed payment processor, and
                mediating disputes.
              </p>
              <p>
                Rentoza does not own, operate, or maintain the vehicles listed on the platform.
                The owner is solely responsible for technical roadworthiness, registration, and
                mandatory third-party liability insurance (AO policy) of their vehicle.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>9. Rentoza Protection</h2>
              <p>
                Rentoza Protection is a contractual guarantee of the Rentoza DOO platform in
                accordance with the Law on Obligations. This is NOT an insurance policy within
                the meaning of the Insurance Law (Official Gazette RS 139/2014).
              </p>
              <h3>Protection tiers:</h3>
              <ul>
                <li>
                  <strong>BASIC (included):</strong> Full deposit amount. Damages are covered
                  from the deposit.
                </li>
                <li>
                  <strong>STANDARD (+10%):</strong> Reduced deposit (50%). Rentoza guarantees
                  coverage up to 150,000 RSD above the deposit.
                </li>
                <li>
                  <strong>PREMIUM (+20%):</strong> No deposit. Rentoza guarantees coverage up
                  to 300,000 RSD.
                </li>
              </ul>
              <p>
                This protection covers material damage to the vehicle incurred during the rental
                period, documented by photo inspection at pickup and return. It does not cover:
                intentional damage, use for illegal activities, operating the vehicle under the
                influence of alcohol or drugs, or damage incurred outside the agreed rental
                period.
              </p>
            </section>

            <mat-divider></mat-divider>

            <section>
              <h2>10. Right of Withdrawal</h2>
              <p>
                In accordance with Article 29(12) of the Consumer Protection Act, the right of
                withdrawal within 14 days does NOT apply to services with an agreed date of
                performance (reservations for a specific date). Free cancellation is available
                up to 24 hours before the rental start — see cancellation policy.
              </p>
            </section>
          }
        </mat-card-content>

        <mat-card-actions>
          <a mat-button routerLink="/legal/privacy-policy">
            <mat-icon>privacy_tip</mat-icon>
            {{ currentLang() === 'sr' ? 'Politika privatnosti' : 'Privacy Policy' }}
          </a>
          <a mat-button routerLink="/">
            <mat-icon>home</mat-icon>
            {{ currentLang() === 'sr' ? 'Početna' : 'Home' }}
          </a>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [
    `
      .terms-container {
        max-width: 900px;
        margin: 24px auto;
        padding: 0 16px;
      }

      .terms-card {
        padding: 24px;
      }

      .lang-toggle {
        display: flex;
        justify-content: flex-end;
        margin-bottom: 24px;
      }

      section {
        margin: 24px 0;

        h2 {
          color: #333;
          font-size: 1.4rem;
          margin-bottom: 16px;
        }

        h3 {
          color: #555;
          font-size: 1.1rem;
          margin: 16px 0 8px;
        }

        p {
          color: #666;
          line-height: 1.6;
        }

        ul {
          color: #666;
          line-height: 1.8;
          padding-left: 24px;

          li {
            margin-bottom: 8px;
          }
        }
      }

      mat-divider {
        margin: 24px 0;
      }

      mat-card-actions {
        display: flex;
        gap: 16px;
        padding: 16px;
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TermsOfServiceComponent {
  readonly lastUpdated = '11. april 2026.';
  readonly lastUpdatedEn = 'April 11, 2026';
  readonly contactEmail = 'legal@rentoza.rs';

  readonly currentLang = signal<'sr' | 'en'>('sr');

  toggleLanguage(): void {
    this.currentLang.update((lang) => (lang === 'sr' ? 'en' : 'sr'));
  }
}