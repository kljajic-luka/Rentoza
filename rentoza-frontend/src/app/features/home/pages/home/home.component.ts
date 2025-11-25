import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnDestroy,
  OnInit,
  inject,
} from '@angular/core';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import {
  MatDatepicker,
  MatDatepickerInputEvent,
  MatDatepickerModule,
} from '@angular/material/datepicker';
import { MatSelectModule } from '@angular/material/select';
import { MatNativeDateModule } from '@angular/material/core';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { Observable, Subscription, filter, map } from 'rxjs';

import { Car } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';

@Component({
  selector: 'app-home-page',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatAutocompleteModule,
    MatDatepickerModule,
    MatSelectModule,
    MatNativeDateModule,
    FlexLayoutModule,
  ],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeComponent implements OnInit, OnDestroy {
  private readonly carService = inject(CarService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);
  private navigationSubscription?: Subscription;

  readonly featuredCars$: Observable<Car[]> = this.carService
    .getCars()
    .pipe(map((cars) => cars.slice(0, 3)));

  // Min date for date pickers (today)
  readonly today = new Date();

  // Static city list for autocomplete
  readonly cities = [
    'Beograd',
    'Novi Sad',
    'Niš',
    'Kragujevac',
    'Subotica',
    'Zrenjanin',
    'Pančevo',
    'Čačak',
    'Kraljevo',
    'Novi Pazar',
    'Šabac',
    'Sombor',
    'Užice',
    'Smederevo',
    'Valjevo',
    'Vranje',
    'Loznica',
    'Požarevac',
    'Pirot',
    'Kruševac',
    'Prokuplje',
    'Jagodina',
    'Bor',
    'Kikinda',
    'Vrbas',
    'Zaječar',
    'Sremska Mitrovica',
    'Vršac',
    'Paraćin',
    'Negotin',
    'Ćuprija',
    'Priboj',
    'Aranđelovac',
    'Leskovac',
    'Gornji Milanovac',
  ];

  // Search form fields with smart defaults
  searchLocation = '';
  searchStartDate: Date | null = null;
  searchStartTime = '';
  searchEndDate: Date | null = null;
  searchEndTime = '';

  // Validation error messages
  locationError = '';
  startDateError = '';
  endDateError = '';
  dateRangeError = '';
  startTimeError = '';
  endTimeError = '';

  // Time options for dropdowns (06:00 - 22:00)
  readonly timeOptions = [
    '06:00',
    '07:00',
    '08:00',
    '09:00',
    '10:00',
    '11:00',
    '12:00',
    '13:00',
    '14:00',
    '15:00',
    '16:00',
    '17:00',
    '18:00',
    '19:00',
    '20:00',
    '21:00',
    '22:00',
  ];

  ngOnInit(): void {
    this.resetSearchFields();

    this.navigationSubscription = this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe((event) => {
        const navEnd = event as NavigationEnd;
        if (this.isHomeUrl(navEnd.urlAfterRedirects)) {
          this.resetSearchFields();
        }
      });
  }

  ngOnDestroy(): void {
    this.navigationSubscription?.unsubscribe();
  }

  get filteredCities(): string[] {
    const query = this.toCanonical(this.searchLocation.trim());

    if (!query) {
      return this.cities;
    }

    return this.cities.filter((city) => this.toCanonical(city).includes(query));
  }

  get datesSelected(): boolean {
    return !!(this.searchStartDate && this.searchEndDate);
  }

  onCitySelected(city: string): void {
    this.searchLocation = city;
    this.validateField('location');
  }

  onStartDateChange(event: MatDatepickerInputEvent<Date>, endPicker: MatDatepicker<Date>): void {
    this.searchStartDate = event.value ?? null;
    this.searchStartTime = '';
    this.searchEndTime = '';
    this.startTimeError = '';
    this.endTimeError = '';

    if (this.searchEndDate && this.searchStartDate && this.searchEndDate <= this.searchStartDate) {
      this.searchEndDate = null;
    }

    this.validateField('startDate');
    this.cdr.markForCheck();

    if (this.searchStartDate) {
      // Prompt user to complete the range
      setTimeout(() => endPicker.open());
    }
  }

  onEndDateChange(event: MatDatepickerInputEvent<Date>): void {
    this.searchEndDate = event.value ?? null;
    this.searchEndTime = '';
    this.endTimeError = '';
    this.validateField('endDate');
    this.cdr.markForCheck();
  }

  private toCanonical(value: string): string {
    return value
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/đ/gi, 'd')
      .toLowerCase();
  }

  private resetSearchFields(): void {
    this.searchLocation = '';
    this.searchStartDate = null;
    this.searchStartTime = '';
    this.searchEndDate = null;
    this.searchEndTime = '';
    this.clearErrors();
    this.cdr.markForCheck();
  }

  private isHomeUrl(url: string): boolean {
    const path = url.split('?')[0].split('#')[0];
    return path === '/' || path === '' || path === '/home';
  }

  searchCars(): void {
    // Clear previous errors
    this.clearErrors();

    const location = this.searchLocation.trim();

    // Required fields validation
    if (!location) {
      this.locationError = 'Unesite lokaciju';
    }

    if (!this.searchStartDate) {
      this.startDateError = 'Izaberite početni datum';
    }

    if (!this.searchEndDate) {
      this.endDateError = 'Izaberite krajnji datum';
    }

    if (!this.searchStartTime) {
      this.startTimeError = 'Izaberite početno vreme';
    }

    if (!this.searchEndTime) {
      this.endTimeError = 'Izaberite krajnje vreme';
    }

    const locationCanonical = this.toCanonical(location);
    const cityMatch = this.cities.some((city) => this.toCanonical(city) === locationCanonical);

    if (location && !cityMatch) {
      this.locationError = 'Odaberite grad iz ponuđenih opcija';
    }

    // Stop if any required validation failed
    if (
      this.locationError ||
      this.startDateError ||
      this.endDateError ||
      this.startTimeError ||
      this.endTimeError
    ) {
      return;
    }

    // Validate date range
    const todayStart = new Date();
    todayStart.setHours(0, 0, 0, 0);

    if ((this.searchStartDate as Date) < todayStart) {
      this.startDateError = 'Početni datum mora biti danas ili kasnije';
      return;
    }

    const startDateTime = new Date(this.searchStartDate as Date);
    startDateTime.setHours(
      parseInt(this.searchStartTime.split(':')[0]),
      parseInt(this.searchStartTime.split(':')[1])
    );

    const endDateTime = new Date(this.searchEndDate as Date);
    endDateTime.setHours(
      parseInt(this.searchEndTime.split(':')[0]),
      parseInt(this.searchEndTime.split(':')[1])
    );

    if (endDateTime <= startDateTime) {
      this.dateRangeError = 'Krajnji datum mora biti posle početnog';
      return;
    }

    // Navigate with availability search params
    const queryParams: any = {
      location,
      startDate: this.formatDate(this.searchStartDate as Date),
      startTime: this.searchStartTime,
      endDate: this.formatDate(this.searchEndDate as Date),
      endTime: this.searchEndTime,
      availabilitySearch: 'true',
    };

    this.router.navigate(['/cars'], { queryParams });
  }

  /**
   * Validate individual field and show error hint
   */
  validateField(field: 'location' | 'startDate' | 'endDate'): void {
    switch (field) {
      case 'location':
        this.locationError = !this.searchLocation.trim() ? 'Unesite lokaciju' : '';
        break;
      case 'startDate':
        this.startDateError = !this.searchStartDate ? 'Izaberite početni datum' : '';
        break;
      case 'endDate':
        this.endDateError = !this.searchEndDate ? 'Izaberite krajnji datum' : '';
        // Also validate range if both dates are set
        if (this.searchStartDate && this.searchEndDate) {
          const start = new Date(this.searchStartDate);
          const end = new Date(this.searchEndDate);
          this.dateRangeError = end <= start ? 'Krajnji datum mora biti posle početnog' : '';
        }
        break;
    }
  }

  /**
   * Clear all validation errors
   */
  private clearErrors(): void {
    this.locationError = '';
    this.startDateError = '';
    this.endDateError = '';
    this.dateRangeError = '';
    this.startTimeError = '';
    this.endTimeError = '';
  }

  /**
   * Format Date object to YYYY-MM-DD string
   */
  private formatDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }
}
