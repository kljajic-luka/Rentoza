import { Component, Inject, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatIconModule } from '@angular/material/icon';

import {
  Car,
  FuelType,
  TransmissionType,
  Feature,
  CancellationPolicy,
} from '@core/models/car.model';
import { CarService } from '@core/services/car.service';

@Component({
  selector: 'app-edit-car-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatIconModule,
  ],
  templateUrl: './edit-car-dialog.component.html',
  styleUrls: ['./edit-car-dialog.component.scss'],
})
export class EditCarDialogComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly carService = inject(CarService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject(MatDialogRef<EditCarDialogComponent>);

  protected readonly isSubmitting = signal(false);
  protected readonly selectedFeatures = signal<Feature[]>([]);

  // Enums for templates
  protected readonly FuelType = FuelType;
  protected readonly TransmissionType = TransmissionType;
  protected readonly CancellationPolicy = CancellationPolicy;
  protected readonly Feature = Feature;

  protected readonly fuelTypes = [
    FuelType.BENZIN,
    FuelType.DIZEL,
    FuelType.ELEKTRIČNI,
    FuelType.HIBRID,
    FuelType.PLUG_IN_HIBRID,
  ];

  protected readonly cancellationPolicies = [
    CancellationPolicy.FLEXIBLE,
    CancellationPolicy.MODERATE,
    CancellationPolicy.STRICT,
    CancellationPolicy.NON_REFUNDABLE,
  ];

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

  protected readonly editForm = this.fb.nonNullable.group({
    brand: ['', [Validators.required, Validators.minLength(2)]],
    model: ['', [Validators.required, Validators.minLength(2)]],
    year: [
      new Date().getFullYear(),
      [Validators.required, Validators.min(1950), Validators.max(2050)],
    ],
    location: ['', [Validators.required, Validators.minLength(2)]],
    // Price field initialized as empty string (not 0) to avoid user having to delete zero
    pricePerDay: ['', [Validators.required, Validators.min(10)]],
    description: ['', [Validators.maxLength(1000)]],
    seats: [5, [Validators.required, Validators.min(2), Validators.max(9)]],
    fuelType: [FuelType.BENZIN, [Validators.required]],
    transmissionType: [TransmissionType.MANUAL, [Validators.required]],
    fuelConsumption: [0, [Validators.min(0), Validators.max(50)]],
    cancellationPolicy: [CancellationPolicy.FLEXIBLE, [Validators.required]],
    minRentalDays: [1, [Validators.required, Validators.min(1)]],
    maxRentalDays: [30, [Validators.required, Validators.min(1)]],
  });

  constructor(@Inject(MAT_DIALOG_DATA) public data: { car: Car }) {}

  ngOnInit(): void {
    // Pre-fill form with existing car data
    const car = this.data.car;
    this.editForm.patchValue({
      brand: car.make,
      model: car.model,
      year: car.year,
      location: car.location,
      // Convert numeric price to string for form control (empty string if null/zero)
      pricePerDay: car.pricePerDay ? String(car.pricePerDay) : '',
      description: car.description || '',
      seats: car.seats || 5,
      fuelType: car.fuelType || FuelType.BENZIN,
      transmissionType: car.transmissionType || TransmissionType.MANUAL,
      fuelConsumption: car.fuelConsumption || 0,
      cancellationPolicy: car.cancellationPolicy || CancellationPolicy.FLEXIBLE,
      minRentalDays: car.minRentalDays || 1,
      maxRentalDays: car.maxRentalDays || 30,
    });

    // Pre-select features
    if (car.features) {
      this.selectedFeatures.set([...car.features]);
    }
  }

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

  protected submitForm(): void {
    if (this.isSubmitting()) return;

    if (this.editForm.invalid) {
      this.snackBar.open('Molimo popunite sva obavezna polja', 'Zatvori', { duration: 3000 });
      return;
    }

    this.isSubmitting.set(true);

    const updatedData: Partial<Car> = {
     make: this.editForm.value.brand!,
     model: this.editForm.value.model!,
     year: this.editForm.value.year!,
     location: this.editForm.value.location!,
     // Convert form value from string back to number for Car model
     pricePerDay: Number(this.editForm.value.pricePerDay!),
     description: this.editForm.value.description,
     seats: this.editForm.value.seats!,
     fuelType: this.editForm.value.fuelType!,
     transmissionType: this.editForm.value.transmissionType!,
     fuelConsumption: this.editForm.value.fuelConsumption,
     features: this.selectedFeatures(),
     cancellationPolicy: this.editForm.value.cancellationPolicy!,
     minRentalDays: this.editForm.value.minRentalDays!,
     maxRentalDays: this.editForm.value.maxRentalDays!,
    };

    this.carService.updateCar(this.data.car.id, updatedData).subscribe({
      next: (updatedCar) => {
        this.carService.clearSearchCache(); // Clear cache to ensure fresh results
        this.snackBar.open('Vozilo uspešno ažurirano!', 'Zatvori', {
          duration: 3000,
          panelClass: ['snackbar-success'],
        });
        this.dialogRef.close(updatedCar);
      },
      error: (error) => {
        console.error('Error updating car:', error);
        this.snackBar.open(
          error.error?.message || 'Greška pri ažuriranju vozila. Pokušajte ponovo.',
          'Zatvori',
          { duration: 5000 }
        );
        this.isSubmitting.set(false);
      },
    });
  }

  protected cancel(): void {
    this.dialogRef.close();
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

  protected translateCancellationPolicy(policy: CancellationPolicy): string {
    const translations: Record<CancellationPolicy, string> = {
      [CancellationPolicy.FLEXIBLE]: 'Fleksibilna',
      [CancellationPolicy.MODERATE]: 'Umerena',
      [CancellationPolicy.STRICT]: 'Striktna',
      [CancellationPolicy.NON_REFUNDABLE]: 'Bez povraćaja',
    };
    return translations[policy];
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