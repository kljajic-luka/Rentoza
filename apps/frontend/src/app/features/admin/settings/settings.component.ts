import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AdminApiService, AdminSettings } from '../../../core/services/admin-api.service';

@Component({
  selector: 'app-admin-settings',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSlideToggleModule,
    MatSelectModule,
    MatIconModule,
    MatDividerModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <div class="admin-page">
      <div class="page-header">
        <div>
          <h1 class="page-title">Admin Settings</h1>
          <p class="page-subtitle">Manage your admin preferences and notifications</p>
        </div>
        <button 
          mat-raised-button 
          color="primary" 
          (click)="saveSettings()"
          [disabled]="saving() || settingsForm.invalid || !settingsForm.dirty"
        >
          <mat-icon>save</mat-icon>
          {{ saving() ? 'Saving...' : 'Save Changes' }}
        </button>
      </div>

      <div class="settings-grid">
        <!-- Notifications Section -->
        <mat-card class="surface-card surface-wide">
          <mat-card-header>
            <mat-icon mat-card-avatar class="section-icon">notifications</mat-icon>
            <mat-card-title>Notifications</mat-card-title>
            <mat-card-subtitle>Configure how you receive updates</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <form [formGroup]="settingsForm" class="settings-form">
              <div class="form-section">
                <mat-slide-toggle 
                  formControlName="emailNotifications"
                  color="primary"
                >
                  <div class="toggle-label">
                    <strong>Email Notifications</strong>
                    <span class="toggle-description">Receive email alerts for important updates</span>
                  </div>
                </mat-slide-toggle>

                <mat-slide-toggle 
                  formControlName="pushNotifications"
                  color="primary"
                >
                  <div class="toggle-label">
                    <strong>Push Notifications</strong>
                    <span class="toggle-description">Get instant browser notifications</span>
                  </div>
                </mat-slide-toggle>

                <mat-slide-toggle 
                  formControlName="smsNotifications"
                  color="primary"
                >
                  <div class="toggle-label">
                    <strong>SMS Notifications</strong>
                    <span class="toggle-description">Receive SMS for critical alerts</span>
                  </div>
                </mat-slide-toggle>
              </div>
            </form>
          </mat-card-content>
        </mat-card>

        <!-- Reports Section -->
        <mat-card class="surface-card surface-wide">
          <mat-card-header>
            <mat-icon mat-card-avatar class="section-icon">assessment</mat-icon>
            <mat-card-title>Reports</mat-card-title>
            <mat-card-subtitle>Configure report generation preferences</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <form [formGroup]="settingsForm" class="settings-form">
              <div class="form-section">
                <mat-slide-toggle 
                  formControlName="weeklyReport"
                  color="primary"
                >
                  <div class="toggle-label">
                    <strong>Weekly Summary Report</strong>
                    <span class="toggle-description">Receive weekly performance summaries</span>
                  </div>
                </mat-slide-toggle>

                <mat-slide-toggle 
                  formControlName="monthlyReport"
                  color="primary"
                >
                  <div class="toggle-label">
                    <strong>Monthly Analytics Report</strong>
                    <span class="toggle-description">Get detailed monthly analytics</span>
                  </div>
                </mat-slide-toggle>

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Report Format</mat-label>
                  <mat-select formControlName="reportFormat">
                    <mat-option value="pdf">PDF Document</mat-option>
                    <mat-option value="csv">CSV Spreadsheet</mat-option>
                    <mat-option value="excel">Excel Workbook</mat-option>
                    <mat-option value="json">JSON Data</mat-option>
                  </mat-select>
                  <mat-icon matPrefix>description</mat-icon>
                  <mat-hint>Choose your preferred report file format</mat-hint>
                </mat-form-field>
              </div>
            </form>
          </mat-card-content>
        </mat-card>

        <!-- Regional Settings -->
        <mat-card class="surface-card surface-wide">
          <mat-card-header>
            <mat-icon mat-card-avatar class="section-icon">language</mat-icon>
            <mat-card-title>Regional Settings</mat-card-title>
            <mat-card-subtitle>Timezone and locale preferences</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <form [formGroup]="settingsForm" class="settings-form">
              <div class="form-section">
                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Timezone</mat-label>
                  <mat-select formControlName="timezone">
                    <mat-option value="UTC">UTC (Coordinated Universal Time)</mat-option>
                    <mat-option value="Europe/Belgrade">Europe/Belgrade (CET)</mat-option>
                    <mat-option value="Europe/London">Europe/London (GMT)</mat-option>
                    <mat-option value="America/New_York">America/New York (EST)</mat-option>
                    <mat-option value="America/Los_Angeles">America/Los Angeles (PST)</mat-option>
                    <mat-option value="Asia/Tokyo">Asia/Tokyo (JST)</mat-option>
                  </mat-select>
                  <mat-icon matPrefix>schedule</mat-icon>
                  <mat-hint>All timestamps will be displayed in this timezone</mat-hint>
                </mat-form-field>

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Currency Display</mat-label>
                  <mat-select formControlName="currencyFormat">
                    <mat-option value="RSD">RSD (Serbian Dinar)</mat-option>
                    <mat-option value="EUR">EUR (Euro)</mat-option>
                    <mat-option value="USD">USD (US Dollar)</mat-option>
                  </mat-select>
                  <mat-icon matPrefix>attach_money</mat-icon>
                  <mat-hint>Preferred currency for reports and displays</mat-hint>
                </mat-form-field>
              </div>
            </form>
          </mat-card-content>
        </mat-card>

        <!-- Account Security -->
        <mat-card class="surface-card surface-wide">
          <mat-card-header>
            <mat-icon mat-card-avatar class="section-icon">security</mat-icon>
            <mat-card-title>Security & Privacy</mat-card-title>
            <mat-card-subtitle>Manage security preferences</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <form [formGroup]="settingsForm" class="settings-form">
              <div class="form-section">
                <mat-slide-toggle 
                  formControlName="twoFactorEnabled"
                  color="primary"
                >
                  <div class="toggle-label">
                    <strong>Two-Factor Authentication</strong>
                    <span class="toggle-description">Add an extra layer of security to your account</span>
                  </div>
                </mat-slide-toggle>

                <mat-slide-toggle 
                  formControlName="loginAlerts"
                  color="primary"
                >
                  <div class="toggle-label">
                    <strong>Login Alerts</strong>
                    <span class="toggle-description">Get notified of new login attempts</span>
                  </div>
                </mat-slide-toggle>

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Session Timeout</mat-label>
                  <mat-select formControlName="sessionTimeout">
                    <mat-option value="15">15 minutes</mat-option>
                    <mat-option value="30">30 minutes</mat-option>
                    <mat-option value="60">1 hour</mat-option>
                    <mat-option value="120">2 hours</mat-option>
                    <mat-option value="480">8 hours</mat-option>
                  </mat-select>
                  <mat-icon matPrefix>timer</mat-icon>
                  <mat-hint>Auto-logout after period of inactivity</mat-hint>
                </mat-form-field>
              </div>
            </form>
          </mat-card-content>
        </mat-card>
      </div>

      <!-- Loading State -->
      <div *ngIf="loading()" class="loading-overlay">
        <mat-progress-spinner mode="indeterminate" diameter="64"></mat-progress-spinner>
        <p>Loading settings...</p>
      </div>

      <!-- Success/Error Messages -->
      <div *ngIf="successMessage()" class="success-banner" role="alert">
        <mat-icon>check_circle</mat-icon>
        <span>{{ successMessage() }}</span>
      </div>

      <div *ngIf="errorMessage()" class="error-banner" role="alert">
        <mat-icon>error</mat-icon>
        <span>{{ errorMessage() }}</span>
      </div>
    </div>
  `,
  styleUrls: ['../admin-shared.styles.scss', './settings.component.scss'],
})
export class AdminSettingsComponent implements OnInit {
  settingsForm: FormGroup;
  loading = signal<boolean>(false);
  saving = signal<boolean>(false);
  successMessage = signal<string>('');
  errorMessage = signal<string>('');

  constructor(
    private fb: FormBuilder,
    private adminApi: AdminApiService
  ) {
    this.settingsForm = this.fb.group({
      // Notifications
      emailNotifications: [true],
      pushNotifications: [false],
      smsNotifications: [false],
      
      // Reports
      weeklyReport: [true],
      monthlyReport: [false],
      reportFormat: ['pdf', Validators.required],
      
      // Regional
      timezone: ['Europe/Belgrade', Validators.required],
      currencyFormat: ['RSD', Validators.required],
      
      // Security
      twoFactorEnabled: [false],
      loginAlerts: [true],
      sessionTimeout: ['60', Validators.required],
    });
  }

  ngOnInit(): void {
    this.loadSettings();
  }

  loadSettings(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.adminApi.getAdminSettings().subscribe({
      next: (settings) => {
        this.settingsForm.patchValue(settings);
        this.settingsForm.markAsPristine();
        this.loading.set(false);
      },
      error: (error: unknown) => {
        console.error('Failed to load settings:', error);
        this.errorMessage.set('Failed to load settings. Using default values.');
        this.loading.set(false);
      },
    });
  }

  saveSettings(): void {
    if (this.settingsForm.invalid) {
      return;
    }

    this.saving.set(true);
    this.successMessage.set('');
    this.errorMessage.set('');

    const settings: AdminSettings = this.settingsForm.value;

    this.adminApi.updateAdminSettings(settings).subscribe({
      next: () => {
        this.successMessage.set('Settings saved successfully!');
        this.settingsForm.markAsPristine();
        this.saving.set(false);
        
        // Clear success message after 3 seconds
        setTimeout(() => this.successMessage.set(''), 3000);
      },
      error: (error: unknown) => {
        console.error('Failed to save settings:', error);
        this.errorMessage.set('Failed to save settings. Please try again.');
        this.saving.set(false);
      },
    });
  }
}
