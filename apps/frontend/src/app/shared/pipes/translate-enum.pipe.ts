import { Pipe, PipeTransform } from '@angular/core';
import { FuelType, TransmissionType, CancellationPolicy, Feature } from '@core/models/car.model';

type SupportedEnum = FuelType | TransmissionType | CancellationPolicy | Feature | string;

/**
 * Pipe to translate enums to Serbian (Latin)
 * Usage: {{ car.fuelType | translateEnum }}
 */
@Pipe({
  name: 'translateEnum',
  standalone: true
})
export class TranslateEnumPipe implements PipeTransform {
  private readonly translations: Record<string, string> = {
    // Fuel Type
    'BENZIN': 'Benzin',
    'DIZEL': 'Dizel',
    'ELEKTRIČNI': 'Električni',
    'HIBRID': 'Hibrid',
    'PLUG_IN_HIBRID': 'Plug-in hibrid',

    // Transmission Type
    'MANUAL': 'Manuelni',
    'AUTOMATIC': 'Automatski',

    // Cancellation Policy
    'FLEXIBLE': 'Fleksibilna - pun povraćaj do 24h pre',
    'MODERATE': 'Umerena - pun povraćaj do 5 dana pre',
    'STRICT': 'Stroga - pun povraćaj do 7 dana pre',
    'NON_REFUNDABLE': 'Bez povraćaja - najbolja cena',

    // Safety Features
    'ABS': 'ABS kočnice',
    'AIRBAG': 'Airbag',
    'PARKING_SENSORS': 'Parking senzori',
    'REVERSE_CAMERA': 'Kamera za rikverc',
    'BLIND_SPOT_MONITOR': 'Monitoring mrtvog ugla',
    'LANE_ASSIST': 'Asistent trake',
    'CRUISE_CONTROL': 'Tempomat',
    'ADAPTIVE_CRUISE': 'Adaptivni tempomat',

    // Connectivity Features
    'BLUETOOTH': 'Bluetooth',
    'USB': 'USB',
    'ANDROID_AUTO': 'Android Auto',
    'APPLE_CARPLAY': 'Apple CarPlay',
    'NAVIGATION': 'Navigacija',
    'WIFI': 'WiFi hotspot',

    // Comfort Features
    'AIR_CONDITIONING': 'Klimatizacija',
    'CLIMATE_CONTROL': 'Dvozonaklima',
    'HEATED_SEATS': 'Grejanje sedišta',
    'LEATHER_SEATS': 'Kožna sedišta',
    'SUNROOF': 'Sunroof',
    'PANORAMIC_ROOF': 'Panoramski krov',
    'KEYLESS_ENTRY': 'Beskljućni ulaz',
    'PUSH_START': 'Startovanje na dugme',
    'ELECTRIC_WINDOWS': 'Električni podizači',
    'POWER_STEERING': 'Servo volan',

    // Additional Features
    'ROOF_RACK': 'Krovni nosač',
    'TOW_HITCH': 'Kuka za vuču',
    'ALLOY_WHEELS': 'Alu felne',
    'LED_LIGHTS': 'LED farovi',
    'FOG_LIGHTS': 'Maglenke'
  };

  transform(value: SupportedEnum | null | undefined): string {
    if (!value) {
      return 'Nema podataka';
    }

    const key = String(value);
    return this.translations[key] || key;
  }
}

/**
 * Helper to categorize features by type
 */
export class FeatureHelper {
  static readonly SAFETY_FEATURES = [
    Feature.ABS,
    Feature.AIRBAG,
    Feature.PARKING_SENSORS,
    Feature.REVERSE_CAMERA,
    Feature.BLIND_SPOT_MONITOR,
    Feature.LANE_ASSIST,
    Feature.CRUISE_CONTROL,
    Feature.ADAPTIVE_CRUISE
  ];

  static readonly CONNECTIVITY_FEATURES = [
    Feature.BLUETOOTH,
    Feature.USB,
    Feature.ANDROID_AUTO,
    Feature.APPLE_CARPLAY,
    Feature.NAVIGATION,
    Feature.WIFI
  ];

  static readonly COMFORT_FEATURES = [
    Feature.AIR_CONDITIONING,
    Feature.CLIMATE_CONTROL,
    Feature.HEATED_SEATS,
    Feature.LEATHER_SEATS,
    Feature.SUNROOF,
    Feature.PANORAMIC_ROOF,
    Feature.KEYLESS_ENTRY,
    Feature.PUSH_START,
    Feature.ELECTRIC_WINDOWS,
    Feature.POWER_STEERING
  ];

  static readonly ADDITIONAL_FEATURES = [
    Feature.ROOF_RACK,
    Feature.TOW_HITCH,
    Feature.ALLOY_WHEELS,
    Feature.LED_LIGHTS,
    Feature.FOG_LIGHTS
  ];

  static categorize(features: Feature[]): {
    safety: Feature[];
    connectivity: Feature[];
    comfort: Feature[];
    additional: Feature[];
  } {
    return {
      safety: features.filter(f => this.SAFETY_FEATURES.includes(f)),
      connectivity: features.filter(f => this.CONNECTIVITY_FEATURES.includes(f)),
      comfort: features.filter(f => this.COMFORT_FEATURES.includes(f)),
      additional: features.filter(f => this.ADDITIONAL_FEATURES.includes(f))
    };
  }
}