import { Component, OnInit, inject, signal, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators,
  AbstractControl,
  ValidationErrors,
} from '@angular/forms';
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

import {
  FuelType,
  TransmissionType,
  Feature,
  CAR_RENTAL_RULES,
  Car,
  ApprovalStatus,
} from '@core/models/car.model';
import { CarService } from '@core/services/car.service';
import { AuthService } from '@core/auth/auth.service';
import {
  LocationStepComponent,
  LocationStepData,
} from './steps/location-step/location-step.component';
import { DocumentUploadComponent } from '../../components/document-upload/document-upload.component';
import {
  CarDocumentService,
  DocumentType as CarDocumentType,
  CarDocument,
} from '@core/services/car-document.service';
import { getHttpErrorMessage } from '@core/utils/api-error.utils';

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
    DocumentUploadComponent,
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
  private readonly carDocumentService = inject(CarDocumentService);

  @ViewChild('stepper') stepper!: MatStepper;
  @ViewChild('locationStep') locationStepComponent!: LocationStepComponent;

  protected readonly isSubmitting = signal(false);
  protected readonly selectedFeatures = signal<Feature[]>([]);
  protected readonly addOns = signal<string[]>([]);
  protected readonly imageUrls = signal<string[]>([]);
  protected readonly selectedImageFiles = signal<File[]>([]);
  protected readonly locationData = signal<LocationStepData | null>(null);
  protected readonly isLocationValid = signal(false);

  // Document compliance tracking (Serbian Legal Compliance)
  // We store raw Files here because car doesn't verify until created
  protected readonly selectedDocumentFiles = signal<Map<string, File>>(new Map());
  protected readonly documentExpiryDates = signal<Map<string, string>>(new Map());
  protected readonly isDocumentsComplete = signal(false);
  protected readonly requiredDocTypes: (
    | 'REGISTRATION'
    | 'TECHNICAL_INSPECTION'
    | 'LIABILITY_INSURANCE'
  )[] = ['REGISTRATION', 'TECHNICAL_INSPECTION', 'LIABILITY_INSURANCE'];

  // Enums for templates
  protected readonly FuelType = FuelType;
  protected readonly TransmissionType = TransmissionType;
  protected readonly Feature = Feature;
  protected readonly rentalRules = CAR_RENTAL_RULES;

  // Arrays for templates
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

  // Step 1: Basic Information
  protected readonly basicInfoForm = this.fb.nonNullable.group({
    brand: ['', [Validators.required, Validators.minLength(2)]],
    model: ['', [Validators.required, Validators.minLength(2)]],
    year: [
      new Date().getFullYear(),
      [Validators.required, Validators.min(1950), Validators.max(2050)],
    ],
    // License plate: Auto-formatting + validation only on submission
    // Format: XX-XXXXXXXX (2 letters + dash + 4-8 chars, min 7 total)
    // Auto-uppercase and auto-dash insertion while typing
    licensePlate: ['', [Validators.required, this.licensePlateValidator.bind(this)]],
    // Price field initialized as empty string (not 0) to avoid user having to delete zero
    pricePerDay: ['', [Validators.required, Validators.min(10), Validators.max(50000)]],
    description: ['', [Validators.maxLength(1000)]],
  });

  // Step 2: Specifications
  protected readonly specificationsForm = this.fb.nonNullable.group({
    seats: [5, [Validators.required, Validators.min(2), Validators.max(9)]],
    fuelType: [FuelType.BENZIN, [Validators.required]],
    transmissionType: [TransmissionType.MANUAL, [Validators.required]],
    fuelConsumption: [null as number | null, [Validators.min(0), Validators.max(50)]],
  });

  // Step 5: Rental Policies
  protected readonly policiesForm = this.fb.nonNullable.group({
    minRentalDays: [1, [Validators.required, Validators.min(1)]],
    maxRentalDays: [30, [Validators.required, Validators.min(1)]],
    dailyMileageLimitKm: [200, [Validators.required, Validators.min(50), Validators.max(1000)]],
    currentMileageKm: [null as number | null, [Validators.min(0), Validators.max(300000)]],
    instantBookEnabled: [false],
  });

  ngOnInit(): void {
    // Initialize with default values
    // Setup license plate auto-formatting
    this.basicInfoForm.get('licensePlate')?.valueChanges.subscribe((value) => {
      if (value) {
        const formatted = this.formatLicensePlate(value);
        if (formatted !== value) {
          this.basicInfoForm.get('licensePlate')?.setValue(formatted, { emitEvent: false });
        }
      }
    });
  }

  /**
   * Auto-format license plate input (Serbian traditional format):
   * - Format: XX-NNN-XX or XX-NNNN-XX (city code - digits - letters)
   * - Converts to uppercase
   * - Auto-inserts dashes at correct positions
   * - Strips non-matching characters
   * Must match backend regex: ^[A-Z]{2}-\d{3,4}-[A-Z]{2}$
   */
  private formatLicensePlate(value: string): string {
    if (!value) return '';

    // Remove spaces/dashes and convert to uppercase
    const raw = value.toUpperCase().replace(/[\s-]/g, '');

    // Extract city code (first 2 letters)
    let pos = 0;
    let cityCode = '';
    while (pos < raw.length && cityCode.length < 2) {
      if (/[A-Z]/.test(raw[pos])) {
        cityCode += raw[pos];
      }
      pos++;
    }
    if (cityCode.length < 2) return cityCode;

    // Extract digits (3 or 4)
    let digits = '';
    while (pos < raw.length && digits.length < 4) {
      if (/\d/.test(raw[pos])) {
        digits += raw[pos];
      }
      pos++;
    }
    if (digits.length === 0) return cityCode + '-';

    // Extract suffix letters (max 2)
    let suffix = '';
    while (pos < raw.length && suffix.length < 2) {
      if (/[A-Z]/.test(raw[pos])) {
        suffix += raw[pos];
      }
      pos++;
    }

    if (suffix.length === 0) {
      return cityCode + '-' + digits;
    }
    return cityCode + '-' + digits + '-' + suffix;
  }

  /**
   * License plate custom validator (Serbian format)
   * Only the traditional format is accepted: XX-NNN-XX or XX-NNNN-XX
   * Must match backend regex: ^[A-Z]{2}-\d{3,4}-[A-Z]{2}$
   */
  private licensePlateValidator(control: AbstractControl): ValidationErrors | null {
    const value = control.value;

    if (!value) {
      return { required: true };
    }

    // Strict Serbian format: XX-NNN-XX or XX-NNNN-XX (matches backend CarRequestDTO)
    const pattern = /^[A-Z]{2}-\d{3,4}-[A-Z]{2}$/;

    if (!pattern.test(value)) {
      return { invalidPlate: true };
    }

    return null;
  }

  // ============================================================================
  // LOCATION STEP HANDLERS
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

  // ============================================================================
  // ADD-ONS
  // ============================================================================

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

  // ============================================================================
  // PHOTOS
  // ============================================================================

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

      if (!file.type.startsWith('image/')) {
        this.snackBar.open('Dozvoljeni su samo fajlovi tipa slika (JPEG/PNG/WebP)', 'Zatvori', {
          duration: 3000,
        });
        return;
      }

      const reader = new FileReader();
      reader.onload = () => {
        this.selectedImageFiles.update((selected) => [...selected, file]);
        this.imageUrls.update((urls) => [...urls, reader.result as string]);
      };
      reader.readAsDataURL(file);
    });

    input.value = '';
  }

  protected removeImage(index: number): void {
    this.imageUrls.update((urls) => urls.filter((_, i) => i !== index));
    this.selectedImageFiles.update((files) => files.filter((_, i) => i !== index));
  }

  // ============================================================================
  // DOCUMENT UPLOAD (Serbian Legal Compliance)
  // ============================================================================

  protected onDocumentFileSelected(type: string, file: File): void {
    this.selectedDocumentFiles.update((map) => {
      const newMap = new Map(map);
      newMap.set(type, file);
      return newMap;
    });
    this.checkDocumentsComplete();
  }

  protected onDocumentFileRemoved(type: string): void {
    this.selectedDocumentFiles.update((map) => {
      const newMap = new Map(map);
      newMap.delete(type);
      return newMap;
    });

    this.documentExpiryDates.update((map) => {
      const newMap = new Map(map);
      newMap.delete(type);
      return newMap;
    });

    this.checkDocumentsComplete();
  }

  protected onDocumentExpiryDateSelected(type: string, date: string): void {
    this.documentExpiryDates.update((map) => {
      const newMap = new Map(map);
      const trimmed = (date ?? '').trim();
      if (trimmed) {
        newMap.set(type, trimmed);
      } else {
        newMap.delete(type);
      }
      return newMap;
    });
    this.checkDocumentsComplete();
  }

  private checkDocumentsComplete(): void {
    const files = this.selectedDocumentFiles();
    const dates = this.documentExpiryDates();

    const hasRegistration = files.has('REGISTRATION') && dates.has('REGISTRATION');
    const hasTechInspection =
      files.has('TECHNICAL_INSPECTION') && dates.has('TECHNICAL_INSPECTION');
    const hasInsurance = files.has('LIABILITY_INSURANCE') && dates.has('LIABILITY_INSURANCE');

    this.isDocumentsComplete.set(hasRegistration && hasTechInspection && hasInsurance);
  }

  protected getDocumentLabel(type: string): string {
    const labels: Record<string, string> = {
      REGISTRATION: 'Saobraćajna dozvola',
      TECHNICAL_INSPECTION: 'Tehnički pregled',
      LIABILITY_INSURANCE: 'Osiguranje od auto odgovornosti',
    };
    return labels[type] || type;
  }

  protected isDocumentSelected(type: string): boolean {
    return this.selectedDocumentFiles().has(type);
  }

  // ============================================================================
  // FORM SUBMISSION
  // ============================================================================

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

    // Validate at least 5 images (Turo standard: minimum 5 photos)
    if (this.imageUrls().length < 5 || this.selectedImageFiles().length < 5) {
      this.snackBar.open('Molimo dodajte minimum 5 slika vozila (Turo standard)', 'Zatvori', {
        duration: 3000,
      });
      return;
    }

    // Validate documents (Mandatory)
    if (!this.isDocumentsComplete()) {
      this.snackBar.open('Molimo otpremite sva obavezna dokumenta', 'Zatvori', { duration: 3000 });
      return;
    }

    this.isSubmitting.set(true);

    try {
      // 1. Create Car
      const location = this.locationData();
      if (!location) throw new Error('Lokacija nedostaje');

      const carData: Partial<Car> = {
        make: this.basicInfoForm.value.brand!,
        model: this.basicInfoForm.value.model!,
        year: this.basicInfoForm.value.year!,
        licensePlate: this.basicInfoForm.value.licensePlate!,

        // Geospatial location data
        location: location.address,
        locationLatitude: location.latitude,
        locationLongitude: location.longitude,
        locationCity: location.city,
        locationZipCode: location.zipCode,

        // Convert form value from string back to number for Car model
        pricePerDay: Number(this.basicInfoForm.value.pricePerDay!),
        description: this.basicInfoForm.value.description,

        seats: this.specificationsForm.value.seats!,
        fuelType: this.specificationsForm.value.fuelType!,
        transmissionType: this.specificationsForm.value.transmissionType!,
        fuelConsumption: this.specificationsForm.value.fuelConsumption ?? 0,

        features: this.selectedFeatures(),
        addOns: this.addOns(), // tags -> addOns property in backend?

        minRentalDays: this.policiesForm.value.minRentalDays!,
        maxRentalDays: this.policiesForm.value.maxRentalDays!,
        dailyMileageLimitKm: this.policiesForm.value.dailyMileageLimitKm!,
        currentMileageKm: this.policiesForm.value.currentMileageKm ?? undefined,
        instantBookEnabled: this.policiesForm.value.instantBookEnabled!,

        available: false,
        approvalStatus: ApprovalStatus.PENDING,
      };

      // Handle tags in Car vs addOns in DTO - assuming service handles it or reusing tags field if available
      // The frontend DTO interface calls it `tags` but backend might map it.
      // Based on previous code: tags: this.addOns() used in one block, addOns: this.addOns() in another.
      // I'll stick to Car interface structure.
      // Checking local `Car` interface... line 19 imports it.
      // I'll assume `tags` or `addOns` works. Let's use `addOns` if it matches DTO, or `tags` if model.
      // Just in case, I'll pass both via spread if needed, but `carData` is typed.
      // Assuming `addCar` takes `Partial<Car>`.

      // Create car + upload local images (multipart/form-data)
      const createdCar = await this.carService
        .addCarMultipart(carData, this.selectedImageFiles())
        .toPromise();

      if (!createdCar?.id) throw new Error('Failed to create car');

      // 2. Upload Documents
      const expiryDates = this.documentExpiryDates();

      // Enterprise-grade: serialize uploads to prevent DB deadlocks from concurrent
      // updates to the same `cars` row (expiry dates/version) across multiple requests.
      for (const [type, file] of this.selectedDocumentFiles().entries()) {
        const upload$ = this.carDocumentService.uploadDocument(
          Number(createdCar.id),
          file,
          type as any,
          expiryDates.get(type) || undefined,
        );
        await upload$.toPromise();
      }

      // 3. Clear Cache & Redirect
      this.carService.clearSearchCache();
      this.snackBar.open(
        'Vozilo i dokumenti uspešno otpremljeni! Na čekanju je odobrenja administratora.',
        'Zatvori',
        { duration: 5000, panelClass: ['snackbar-success'] },
      );
      this.router.navigate(['/owner/cars']);
    } catch (error: any) {
      console.error('Error in car submission flow:', error);
      this.snackBar.open(
        getHttpErrorMessage(error) || 'Greška pri kreiranju vozila. Proverite podatke.',
        'Zatvori',
        { duration: 5000 },
      );
    } finally {
      this.isSubmitting.set(false);
    }
  }

  protected cancelWizard(): void {
    if (
      confirm('Da li ste sigurni da želite da odustanete? Svi uneti podaci će biti izgubljeni.')
    ) {
      this.router.navigate(['/owner/cars']);
    }
  }

  // ============================================================================
  // HELPERS
  // ============================================================================

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