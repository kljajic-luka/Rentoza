import { FuelType, TransmissionType, Feature, CancellationPolicy } from '@core/models/car.model';

/**
 * Serbian translations for car attributes
 * Uses Latin script as per Serbian market standards
 */

/**
 * Fuel type translations
 */
export const FUEL_TYPE_LABELS: Record<FuelType, string> = {
  [FuelType.BENZIN]: 'Benzin',
  [FuelType.DIZEL]: 'Dizel',
  [FuelType.ELEKTRIČNI]: 'Električni',
  [FuelType.HIBRID]: 'Hibrid',
  [FuelType.PLUG_IN_HIBRID]: 'Plug-in hibrid'
};

/**
 * Transmission type translations
 */
export const TRANSMISSION_TYPE_LABELS: Record<TransmissionType, string> = {
  [TransmissionType.MANUAL]: 'Manuelni',
  [TransmissionType.AUTOMATIC]: 'Automatski'
};

/**
 * Feature translations with icons
 */
export const FEATURE_LABELS: Record<Feature, { label: string; icon: string; category: string }> = {
  // Safety Features
  [Feature.ABS]: { label: 'ABS', icon: 'security', category: 'Sigurnost' },
  [Feature.AIRBAG]: { label: 'Airbag', icon: 'safety_check', category: 'Sigurnost' },
  [Feature.PARKING_SENSORS]: { label: 'Parking senzori', icon: 'sensors', category: 'Sigurnost' },
  [Feature.REVERSE_CAMERA]: { label: 'Kamera za vožnju unazad', icon: 'videocam', category: 'Sigurnost' },
  [Feature.BLIND_SPOT_MONITOR]: { label: 'Monitoring mrtvog ugla', icon: 'visibility', category: 'Sigurnost' },
  [Feature.LANE_ASSIST]: { label: 'Pomoć za održavanje trake', icon: 'alt_route', category: 'Sigurnost' },
  [Feature.CRUISE_CONTROL]: { label: 'Tempomat', icon: 'speed', category: 'Sigurnost' },
  [Feature.ADAPTIVE_CRUISE]: { label: 'Adaptivni tempomat', icon: 'adjust', category: 'Sigurnost' },

  // Connectivity Features
  [Feature.BLUETOOTH]: { label: 'Bluetooth', icon: 'bluetooth', category: 'Povezanost' },
  [Feature.USB]: { label: 'USB priključak', icon: 'usb', category: 'Povezanost' },
  [Feature.ANDROID_AUTO]: { label: 'Android Auto', icon: 'android', category: 'Povezanost' },
  [Feature.APPLE_CARPLAY]: { label: 'Apple CarPlay', icon: 'phone_iphone', category: 'Povezanost' },
  [Feature.NAVIGATION]: { label: 'GPS navigacija', icon: 'navigation', category: 'Povezanost' },
  [Feature.WIFI]: { label: 'WiFi hotspot', icon: 'wifi', category: 'Povezanost' },

  // Comfort Features
  [Feature.AIR_CONDITIONING]: { label: 'Klima uređaj', icon: 'ac_unit', category: 'Komfor' },
  [Feature.CLIMATE_CONTROL]: { label: 'Dvozosnska klimatizacija', icon: 'thermostat', category: 'Komfor' },
  [Feature.HEATED_SEATS]: { label: 'Grejanje sedišta', icon: 'airline_seat_recline_extra', category: 'Komfor' },
  [Feature.LEATHER_SEATS]: { label: 'Kožna sedišta', icon: 'event_seat', category: 'Komfor' },
  [Feature.SUNROOF]: { label: 'Krovni prozor', icon: 'wb_sunny', category: 'Komfor' },
  [Feature.PANORAMIC_ROOF]: { label: 'Panoramski krov', icon: 'panorama', category: 'Komfor' },
  [Feature.KEYLESS_ENTRY]: { label: 'Beskontaktno otključavanje', icon: 'key', category: 'Komfor' },
  [Feature.PUSH_START]: { label: 'Start dugme', icon: 'power_settings_new', category: 'Komfor' },
  [Feature.ELECTRIC_WINDOWS]: { label: 'Električni podizači stakla', icon: 'window', category: 'Komfor' },
  [Feature.POWER_STEERING]: { label: 'Servo volan', icon: 'settings', category: 'Komfor' },

  // Additional Features
  [Feature.ROOF_RACK]: { label: 'Krovni nosač', icon: 'luggage', category: 'Dodatno' },
  [Feature.TOW_HITCH]: { label: 'Kuka za vuču', icon: 'link', category: 'Dodatno' },
  [Feature.ALLOY_WHEELS]: { label: 'Alu felge', icon: 'trip_origin', category: 'Dodatno' },
  [Feature.LED_LIGHTS]: { label: 'LED farovi', icon: 'lightbulb', category: 'Dodatno' },
  [Feature.FOG_LIGHTS]: { label: 'Maglenke', icon: 'light_mode', category: 'Dodatno' }
};

/**
 * Cancellation policy descriptions
 */
export const CANCELLATION_POLICY_INFO: Record<CancellationPolicy, { title: string; description: string; icon: string }> = {
  [CancellationPolicy.FLEXIBLE]: {
    title: 'Fleksibilno',
    description: 'Pun povraćaj novca do 24 sata pre preuzimanja vozila.',
    icon: 'check_circle'
  },
  [CancellationPolicy.MODERATE]: {
    title: 'Umereno',
    description: 'Pun povraćaj do 5 dana pre preuzimanja. 50% povraćaj 24-120 sati pre.',
    icon: 'schedule'
  },
  [CancellationPolicy.STRICT]: {
    title: 'Strogo',
    description: 'Pun povraćaj do 7 dana pre. 50% povraćaj 7-14 dana pre. Bez povraćaja u roku od 7 dana.',
    icon: 'block'
  },
  [CancellationPolicy.NON_REFUNDABLE]: {
    title: 'Bez povraćaja',
    description: 'Bez povraćaja novca. Najbolja cena.',
    icon: 'money_off'
  }
};

/**
 * Get fuel type label
 */
export function getFuelTypeLabel(fuelType?: FuelType): string {
  return fuelType ? FUEL_TYPE_LABELS[fuelType] : 'N/A';
}

/**
 * Get transmission type label
 */
export function getTransmissionTypeLabel(transmissionType?: TransmissionType): string {
  return transmissionType ? TRANSMISSION_TYPE_LABELS[transmissionType] : 'N/A';
}

/**
 * Get feature label
 */
export function getFeatureLabel(feature: Feature): string {
  return FEATURE_LABELS[feature]?.label || feature;
}

/**
 * Get feature icon
 */
export function getFeatureIcon(feature: Feature): string {
  return FEATURE_LABELS[feature]?.icon || 'check_circle';
}

/**
 * Get feature category
 */
export function getFeatureCategory(feature: Feature): string {
  return FEATURE_LABELS[feature]?.category || 'Ostalo';
}

/**
 * Group features by category
 */
export function groupFeaturesByCategory(features: Feature[]): Map<string, Feature[]> {
  const grouped = new Map<string, Feature[]>();

  features.forEach(feature => {
    const category = getFeatureCategory(feature);
    if (!grouped.has(category)) {
      grouped.set(category, []);
    }
    grouped.get(category)!.push(feature);
  });

  return grouped;
}

/**
 * Get cancellation policy info
 */
export function getCancellationPolicyInfo(policy?: CancellationPolicy) {
  return policy ? CANCELLATION_POLICY_INFO[policy] : {
    title: 'Nije navedeno',
    description: 'Kontaktirajte domaćina za detalje.',
    icon: 'info'
  };
}

/**
 * Format fuel consumption for display
 */
export function formatFuelConsumption(consumption?: number): string {
  return consumption ? `${consumption.toFixed(1)} L/100km` : 'Nije navedeno';
}

/**
 * Get icon for fuel type
 */
export function getFuelTypeIcon(fuelType?: FuelType): string {
  switch (fuelType) {
    case FuelType.BENZIN:
    case FuelType.DIZEL:
      return 'local_gas_station';
    case FuelType.ELEKTRIČNI:
      return 'electric_car';
    case FuelType.HIBRID:
    case FuelType.PLUG_IN_HIBRID:
      return 'hybrid';
    default:
      return 'directions_car';
  }
}

/**
 * Get icon for transmission type
 */
export function getTransmissionTypeIcon(transmissionType?: TransmissionType): string {
  return transmissionType === TransmissionType.AUTOMATIC ? 'autorenew' : 'settings';
}