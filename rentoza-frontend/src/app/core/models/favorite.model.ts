/**
 * Favorite model matching backend DTO
 */
export interface Favorite {
  id: number;
  userId: number;
  carId: number;

  // Embedded car information
  carBrand: string;
  carModel: string;
  carYear: number;
  carPricePerDay: number;
  carLocation: string;
  carImageUrl?: string;
  carAvailable: boolean;

  createdAt: string;
}

/**
 * Response from toggle favorite endpoint
 */
export interface FavoriteToggleResponse {
  isFavorited: boolean;
  message: string;
}
