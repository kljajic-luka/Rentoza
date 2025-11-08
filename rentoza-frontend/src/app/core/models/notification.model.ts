/**
 * Notification model matching backend NotificationResponseDTO.
 */
export interface Notification {
  id: number;
  type: NotificationType;
  message: string;
  relatedEntityId: string | null;
  read: boolean;
  createdAt: string; // ISO 8601 timestamp
}

/**
 * Notification types matching backend NotificationType enum.
 */
export enum NotificationType {
  BOOKING_CONFIRMED = 'BOOKING_CONFIRMED',
  BOOKING_CANCELLED = 'BOOKING_CANCELLED',
  NEW_MESSAGE = 'NEW_MESSAGE',
  REVIEW_RECEIVED = 'REVIEW_RECEIVED',
}

/**
 * Request to register a device token for push notifications.
 */
export interface RegisterDeviceTokenRequest {
  deviceToken: string;
  platform: DevicePlatform;
}

/**
 * Device platform types.
 */
export enum DevicePlatform {
  WEB = 'WEB',
  ANDROID = 'ANDROID',
  IOS = 'IOS',
}

/**
 * Unread count response.
 */
export interface UnreadCountResponse {
  count: number;
}

/**
 * Generic success response.
 */
export interface NotificationSuccessResponse {
  message: string;
  count?: number;
}
