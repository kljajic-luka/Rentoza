import { Injectable, inject, DestroyRef } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { tap, catchError } from 'rxjs/operators';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { ToastrService } from 'ngx-toastr';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { environment } from '@env/environment';
import {
  Notification,
  RegisterDeviceTokenRequest,
  UnreadCountResponse,
  NotificationSuccessResponse,
  NotificationType,
} from '@core/models/notification.model';

/**
 * Service for managing notifications and WebSocket subscription.
 *
 * Features:
 * - Fetch notifications from REST API
 * - Subscribe to WebSocket notifications
 * - Manage unread count
 * - Mark notifications as read
 * - Register/unregister FCM device tokens
 */
@Injectable({
  providedIn: 'root',
})
export class NotificationService {
  private readonly http = inject(HttpClient);
  private readonly toastr = inject(ToastrService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly baseUrl = `${environment.baseApiUrl}/notifications`;

  // State management
  private unreadCountSubject = new BehaviorSubject<number>(0);
  public unreadCount$ = this.unreadCountSubject.asObservable();

  private notificationsSubject = new BehaviorSubject<Notification[]>([]);
  public notifications$ = this.notificationsSubject.asObservable();

  // WebSocket
  private stompClient: Client | null = null;
  private notificationSubscription: StompSubscription | null = null;

  /**
   * Initialize WebSocket connection for real-time notifications.
   * Should be called after user login.
   */
  connectWebSocket(): void {
    if (this.stompClient?.connected) {
      console.log('WebSocket already connected');
      return;
    }

    this.stompClient = new Client({
      brokerURL: environment.wsUrl,
      debug: (str) => console.log('STOMP Debug:', str),
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
    });

    this.stompClient.onConnect = () => {
      console.log('WebSocket connected for notifications');

      // Subscribe to user-specific notification queue
      this.notificationSubscription = this.stompClient!.subscribe(
        '/user/queue/notifications',
        (message: IMessage) => this.handleNotificationMessage(message)
      );

      // Fetch initial unread count and recent notifications
      this.loadUnreadCount();
      this.loadRecentNotifications();
    };

    this.stompClient.onStompError = (frame) => {
      console.error('WebSocket error:', frame);
      this.toastr.error('Greška pri povezivanju za obaveštenja');
    };

    this.stompClient.activate();
  }

  /**
   * Disconnect WebSocket connection.
   * Should be called on logout.
   */
  disconnectWebSocket(): void {
    if (this.notificationSubscription) {
      this.notificationSubscription.unsubscribe();
      this.notificationSubscription = null;
    }

    if (this.stompClient) {
      this.stompClient.deactivate();
      this.stompClient = null;
    }

    console.log('WebSocket disconnected');
  }

  /**
   * Handle incoming WebSocket notification message.
   */
  private handleNotificationMessage(message: IMessage): void {
    try {
      const notification: Notification = JSON.parse(message.body);
      console.log('Received notification:', notification);

      // Update state
      const currentNotifications = this.notificationsSubject.value;
      this.notificationsSubject.next([notification, ...currentNotifications]);

      // Increment unread count
      this.unreadCountSubject.next(this.unreadCountSubject.value + 1);

      // Show toast notification
      this.showToastForNotification(notification);
    } catch (error) {
      console.error('Failed to parse notification message:', error);
    }
  }

  /**
   * Show toast notification based on type.
   */
  private showToastForNotification(notification: Notification): void {
    const config = {
      timeOut: 5000,
      progressBar: true,
      closeButton: true,
    };

    switch (notification.type) {
      case NotificationType.BOOKING_CONFIRMED:
        this.toastr.success(notification.message, 'Rezervacija potvrđena', config);
        break;
      case NotificationType.BOOKING_CANCELLED:
        this.toastr.warning(notification.message, 'Rezervacija otkazana', config);
        break;
      case NotificationType.NEW_MESSAGE:
        this.toastr.info(notification.message, 'Nova poruka', config);
        break;
      case NotificationType.REVIEW_RECEIVED:
        this.toastr.info(notification.message, 'Nova recenzija', config);
        break;
      default:
        this.toastr.info(notification.message, 'Obaveštenje', config);
    }
  }

  /**
   * Load unread notification count from API.
   */
  loadUnreadCount(): void {
    this.http
      .get<UnreadCountResponse>(`${this.baseUrl}/unread/count`, {
        withCredentials: true,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.unreadCountSubject.next(response.count),
        error: (error) => console.error('Failed to load unread count:', error),
      });
  }

  /**
   * Load recent unread notifications from API.
   */
  loadRecentNotifications(): void {
    this.http
      .get<Notification[]>(`${this.baseUrl}/unread`, {
        withCredentials: true,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (notifications) => this.notificationsSubject.next(notifications),
        error: (error) => console.error('Failed to load notifications:', error),
      });
  }

  /**
   * Get paginated notifications.
   */
  getNotifications(page: number = 0, size: number = 20): Observable<Notification[]> {
    return this.http.get<Notification[]>(`${this.baseUrl}?page=${page}&size=${size}`, {
      withCredentials: true,
    });
  }

  /**
   * Mark a notification as read.
   */
  markAsRead(notificationId: number): Observable<NotificationSuccessResponse> {
    return this.http
      .patch<NotificationSuccessResponse>(
        `${this.baseUrl}/${notificationId}/read`,
        {},
        { withCredentials: true }
      )
      .pipe(
        tap(() => {
          // Update local state
          const notifications = this.notificationsSubject.value.map((n) =>
            n.id === notificationId ? { ...n, read: true } : n
          );
          this.notificationsSubject.next(notifications);

          // Decrement unread count
          const currentCount = this.unreadCountSubject.value;
          if (currentCount > 0) {
            this.unreadCountSubject.next(currentCount - 1);
          }
        }),
        catchError((error) => {
          console.error('Failed to mark notification as read:', error);
          throw error;
        })
      );
  }

  /**
   * Mark all notifications as read.
   */
  markAllAsRead(): Observable<NotificationSuccessResponse> {
    return this.http
      .post<NotificationSuccessResponse>(
        `${this.baseUrl}/mark-all-read`,
        {},
        { withCredentials: true }
      )
      .pipe(
        tap(() => {
          // Update local state
          const notifications = this.notificationsSubject.value.map((n) => ({ ...n, read: true }));
          this.notificationsSubject.next(notifications);
          this.unreadCountSubject.next(0);
        }),
        catchError((error) => {
          console.error('Failed to mark all as read:', error);
          throw error;
        })
      );
  }

  /**
   * Register FCM device token for push notifications.
   */
  registerDeviceToken(request: RegisterDeviceTokenRequest): Observable<NotificationSuccessResponse> {
    return this.http.post<NotificationSuccessResponse>(
      `${this.baseUrl}/register-token`,
      request,
      { withCredentials: true }
    );
  }

  /**
   * Unregister device token.
   */
  unregisterDeviceToken(deviceToken: string): Observable<NotificationSuccessResponse> {
    return this.http.delete<NotificationSuccessResponse>(
      `${this.baseUrl}/unregister-token`,
      {
        body: { deviceToken },
        withCredentials: true,
      }
    );
  }
}
