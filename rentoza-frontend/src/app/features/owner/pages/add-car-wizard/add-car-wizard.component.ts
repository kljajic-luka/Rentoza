import { Component, OnInit, inject, signal, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatRadioModule } from '@angular/material/radio';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { STEPPER_GLOBAL_OPTIONS } from '@angular/cdk/stepper';

import { FuelType, TransmissionType, Feature, CAR_RENTAL_RULES, Car } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';
import { AuthService } from '@core/auth/auth.service';
import {
  LocationStepComponent,
  LocationStepData,
} from './steps/location-step/location-step.component';

@Component({
  selector: 'app-add-car-wizard',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterLink,
    MatStepperModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatRadioModule,
    MatCheckboxModule,
    MatChipsModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    LocationStepComponent,
  ],
  providers: [
    {
      provide: STEPPER_GLOBAL_OPTIONS,
      useValue: { displayDefaultIndicatorType: false, showError: true },
    },
  ],
  templateUrl: './add-car-wizard.component.html',
  styleUrls: ['./add-car-wizard.component.scss'],
})
export class AddCarWizardComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly carService = inject(CarService);
  private readonly authService = inject(AuthService);

  @ViewChild('stepper') stepper!: MatStepper;
  @ViewChild('locationStep') locationStepComponent!: LocationStepComponent;

  protected readonly isSubmitting = signal(false);
  protected readonly selectedFeatures = signal<Feature[]>([]);
  protected readonly addOns = signal<string[]>([]);
  protected readonly imageUrls = signal<string[]>([]);
  protected readonly locationData = signal<LocationStepData | null>(null);
  protected readonly isLocationValid = signal(false);

  // Enums for templates
  protected readonly FuelType = FuelType;
  protected readonly TransmissionType = TransmissionType;
  protected readonly Feature = Feature;
  protected readonly rentalRules = CAR_RENTAL_RULES;

  // Arrays for templates (to avoid Serbian characters in template)
  protected readonly fuelTypes = [
    FuelType.BENZIN,
    FuelType.DIZEL,
    FuelType.ELEKTRIČNI,
    FuelType.HIBRID,
    FuelType.PLUG_IN_HIBRID,
  ];

  // Grouped features
  protected readonly featureGroups = {
    safety: [
      Feature.ABS,
      Feature.AIRBAG,
      Feature.PARKING_SENSORS,
      Feature.REVERSE_CAMERA,
      Feature.BLIND_SPOT_MONITOR,
      Feature.LANE_ASSIST,
      Feature.CRUISE_CONTROL,
      Feature.ADAPTIVE_CRUISE,
    ],
    connectivity: [
      Feature.BLUETOOTH,
      Feature.USB,
      Feature.ANDROID_AUTO,
      Feature.APPLE_CARPLAY,
      Feature.NAVIGATION,
      Feature.WIFI,
    ],
    comfort: [
      Feature.AIR_CONDITIONING,
      Feature.CLIMATE_CONTROL,
      Feature.HEATED_SEATS,
      Feature.LEATHER_SEATS,
      Feature.SUNROOF,
      Feature.PANORAMIC_ROOF,
      Feature.KEYLESS_ENTRY,
      Feature.PUSH_START,
      Feature.ELECTRIC_WINDOWS,
      Feature.POWER_STEERING,
    ],
    additional: [
      Feature.ROOF_RACK,
      Feature.TOW_HITCH,
      Feature.ALLOY_WHEELS,
      Feature.LED_LIGHTS,
      Feature.FOG_LIGHTS,
    ],
  };

  // Step 1: Basic Information (location moved to separate step)
  protected readonly basicInfoForm = this.fb.nonNullable.group({
    brand: ['', [Validators.required, Validators.minLength(2)]],
    model: ['', [Validators.required, Validators.minLength(2)]],
    year: [
      new Date().getFullYear(),
      [Validators.required, Validators.min(1950), Validators.max(2050)],
    ],
    licensePlate: ['', [Validators.required, Validators.pattern('^[A-Z]{2}-[0-9]{3,4}-[A-Z]{2}$')]],
    pricePerDay: [0, [Validators.required, Validators.min(10)]],
    description: ['', [Validators.maxLength(1000)]],
  });

  // Step 2: Specifications
  protected readonly specificationsForm = this.fb.nonNullable.group({
    seats: [5, [Validators.required, Validators.min(2), Validators.max(9)]],
    fuelType: [FuelType.BENZIN, [Validators.required]],
    transmissionType: [TransmissionType.MANUAL, [Validators.required]],
    fuelConsumption: [0, [Validators.min(0), Validators.max(50)]],
  });

  // Step 5: Rental Policies (simplified - no cancellation policy selection)
  // Note: Cancellation policy is now platform-controlled (Turo-style) per migration plan.
  protected readonly policiesForm = this.fb.nonNullable.group({
    minRentalDays: [1, [Validators.required, Validators.min(1)]],
    maxRentalDays: [30, [Validators.required, Validators.min(1)]],
  });

  ngOnInit(): void {
    // Initialize with default values
  }

  // ============================================================================
  // LOCATION STEP HANDLERS (Phase 2.4 Geospatial)
  // ============================================================================

  protected onLocationSelected(location: LocationStepData): void {
    this.locationData.set(location);
  }

  protected onLocationValidityChanged(isValid: boolean): void {
    this.isLocationValid.set(isValid);
  }

  // ============================================================================
  // FEATURE SELECTION
  // ============================================================================

  // Feature Selection
  protected toggleFeature(feature: Feature): void {
    const current = this.selectedFeatures();
    const index = current.indexOf(feature);

    if (index === -1) {
      this.selectedFeatures.set([...current, feature]);
    } else {
      this.selectedFeatures.set(current.filter((f) => f !== feature));
    }
  }

  protected isFeatureSelected(feature: Feature): boolean {
    return this.selectedFeatures().includes(feature);
  }

  // Add-ons Management
  protected addAddOn(input: HTMLInputElement): void {
    const value = input.value.trim();
    if (value && !this.addOns().includes(value)) {
      this.addOns.update((addOns) => [...addOns, value]);
      input.value = '';
    }
  }

  protected removeAddOn(addOn: string): void {
    this.addOns.update((addOns) => addOns.filter((a) => a !== addOn));
  }

  // Photo Management
  protected handleFileSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files) return;

    const files = Array.from(input.files);
    const maxFiles = 10 - this.imageUrls().length;

    files.slice(0, maxFiles).forEach((file) => {
      if (file.size > 5 * 1024 * 1024) {
        this.snackBar.open('Slika je prevelika (max 5MB)', 'Zatvori', { duration: 3000 });
        return;
      }

      const reader = new FileReader();
      reader.onload = () => {
        this.imageUrls.update((urls) => [...urls, reader.result as string]);
      };
      reader.readAsDataURL(file);
    });

    input.value = '';
  }

  protected removeImage(index: number): void {
    this.imageUrls.update((urls) => urls.filter((_, i) => i !== index));
  }

  // Form Submission
  protected async submitForm(): Promise<void> {
    if (this.isSubmitting()) return;

    // Validate all steps
    if (
      this.basicInfoForm.invalid ||
      this.specificationsForm.invalid ||
      this.policiesForm.invalid ||
      !this.isLocationValid()
    ) {
      this.snackBar.open('Molimo popunite sva obavezna polja', 'Zatvori', { duration: 3000 });
      return;
    }

    // Validate at least one image
    if (this.imageUrls().length === 0) {
      this.snackBar.open('Molimo dodajte bar jednu sliku vozila', 'Zatvori', { duration: 3000 });
      return;
    }

    // Validate location data
    const location = this.locationData();
    if (!location) {
      this.snackBar.open('Molimo unesite lokaciju vozila', 'Zatvori', { duration: 3000 });
      return;
    }

    this.isSubmitting.set(true);

    const carData: Partial<Car> = {
      make: this.basicInfoForm.value.brand!,
      model: this.basicInfoForm.value.model!,
      year: this.basicInfoForm.value.year!,
      licensePlate: this.basicInfoForm.value.licensePlate!,
      // Geospatial location data (Phase 2.4)
      location: location.address, // Legacy string field for backward compatibility
      locationLatitude: location.latitude,
      locationLongitude: location.longitude,
      locationCity: location.city,
      locationZipCode: location.zipCode,
      pricePerDay: this.basicInfoForm.value.pricePerDay!,
      description: this.basicInfoForm.value.description,
      seats: this.specificationsForm.value.seats!,
      fuelType: this.specificationsForm.value.fuelType!,
      transmissionType: this.specificationsForm.value.transmissionType!,
      fuelConsumption: this.specificationsForm.value.fuelConsumption,
      features: this.selectedFeatures(),
      addOns: this.addOns(),
      // Note: cancellationPolicy removed - now platform-controlled (Turo-style)
      minRentalDays: this.policiesForm.value.minRentalDays!,
      maxRentalDays: this.policiesForm.value.maxRentalDays!,
      imageUrls: this.imageUrls(),
      imageUrl: this.imageUrls()[0], // Primary image
      available: true, // New cars are available by default
    };

    this.carService.addCar(carData).subscribe({
      next: (car) => {
        this.carService.clearSearchCache(); // Clear cache to ensure fresh results
        this.snackBar.open('Vozilo uspešno dodato! Na čekanju je za odobrenje.', 'Zatvori', {
          duration: 4000,
          panelClass: ['snackbar-success'],
        });
        this.router.navigate(['/owner/cars']);
      },
      error: (error) => {
        console.error('Error adding car:', error);
        this.snackBar.open(
          error.error?.message || 'Greška pri dodavanju vozila. Pokušajte ponovo.',
          'Zatvori',
          { duration: 5000 }
        );
        this.isSubmitting.set(false);
      },
    });
  }

  protected cancelWizard(): void {
    if (
      confirm('Da li ste sigurni da želite da odustanete? Svi uneti podaci će biti izgubljeni.')
    ) {
      this.router.navigate(['/owner/cars']);
    }
  }

  // Translation helpers
  protected translateFuelType(fuelType: FuelType): string {
    const translations: Record<FuelType, string> = {
      [FuelType.BENZIN]: 'Benzin',
      [FuelType.DIZEL]: 'Dizel',
      [FuelType.ELEKTRIČNI]: 'Električni',
      [FuelType.HIBRID]: 'Hibrid',
      [FuelType.PLUG_IN_HIBRID]: 'Plug-in Hibrid',
    };
    return translations[fuelType];
  }

  protected translateTransmission(transmission: TransmissionType): string {
    return transmission === TransmissionType.MANUAL ? 'Manuelni' : 'Automatik';
  }

  protected translateFeature(feature: Feature): string {
    // Serbian translations for all features
    const translations: Record<Feature, string> = {
      [Feature.ABS]: 'ABS',
      [Feature.AIRBAG]: 'Vazdušni jastuci',
      [Feature.PARKING_SENSORS]: 'Senzori za parkiranje',
      [Feature.REVERSE_CAMERA]: 'Kamera za vožnju unazad',
      [Feature.BLIND_SPOT_MONITOR]: 'Monitor mrtvog ugla',
      [Feature.LANE_ASSIST]: 'Asistent trake',
      [Feature.CRUISE_CONTROL]: 'Tempomat',
      [Feature.ADAPTIVE_CRUISE]: 'Adaptivni tempomat',
      [Feature.BLUETOOTH]: 'Bluetooth',
      [Feature.USB]: 'USB',
      [Feature.ANDROID_AUTO]: 'Android Auto',
      [Feature.APPLE_CARPLAY]: 'Apple CarPlay',
      [Feature.NAVIGATION]: 'Navigacija',
      [Feature.WIFI]: 'WiFi',
      [Feature.AIR_CONDITIONING]: 'Klima',
      [Feature.CLIMATE_CONTROL]: 'Automatska klima',
      [Feature.HEATED_SEATS]: 'Grejanje sedišta',
      [Feature.LEATHER_SEATS]: 'Kožna sedišta',
      [Feature.SUNROOF]: 'Krovni prozor',
      [Feature.PANORAMIC_ROOF]: 'Panoramski krov',
      [Feature.KEYLESS_ENTRY]: 'Bezključno otključavanje',
      [Feature.PUSH_START]: 'Start-stop dugme',
      [Feature.ELECTRIC_WINDOWS]: 'Električni podizači',
      [Feature.POWER_STEERING]: 'Servo volan',
      [Feature.ROOF_RACK]: 'Krovni nosač',
      [Feature.TOW_HITCH]: 'Kuka za vuču',
      [Feature.ALLOY_WHEELS]: 'Aluminijumske felne',
      [Feature.LED_LIGHTS]: 'LED svetla',
      [Feature.FOG_LIGHTS]: 'Maglenke',
    };
    return translations[feature];
  }
}
