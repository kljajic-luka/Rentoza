import { Injectable, inject, OnDestroy } from '@angular/core';
import { Client, StompSubscription, messageCallbackType } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { BehaviorSubject, Observable, Subject, timer, Subscription } from 'rxjs';
import { filter, takeUntil } from 'rxjs/operators';
import { environment } from '@environments/environment';
import { AuthService } from '@core/auth/auth.service';
import { ToastrService } from 'ngx-toastr';

export enum WebSocketConnectionStatus {
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED',
  DISCONNECTED = 'DISCONNECTED',
  ERROR = 'ERROR',
  RECONNECTING = 'RECONNECTING',
}

interface ReconnectionConfig {
  maxAttempts: number;
  initialDelay: number;
  maxDelay: number;
  backoffMultiplier: number;
}

@Injectable({
  providedIn: 'root',
})
export class WebSocketService implements OnDestroy {
  private readonly authService = inject(AuthService);
  private readonly toastr = inject(ToastrService);

  private stompClient: Client | null = null;
  private connectionStatus$ = new BehaviorSubject<WebSocketConnectionStatus>(
    WebSocketConnectionStatus.DISCONNECTED
  );
  private subscriptions = new Map<string, StompSubscription>();
  private messageSubject = new Subject<{ destination: string; body: any }>();
  private destroy$ = new Subject<void>();

  private reconnectionAttempts = 0;
  private reconnectionConfig: ReconnectionConfig = {
    maxAttempts: 10,
    initialDelay: 1000,
    maxDelay: 30000,
    backoffMultiplier: 1.5,
  };
  private reconnectionTimer: Subscription | null = null;
  private isConnecting = false;
  private isManualDisconnect = false;

  public status$ = this.connectionStatus$.asObservable();
  public messages$ = this.messageSubject.asObservable();

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.disconnect(true);
  }

  /**
   * Connect to WebSocket server with JWT authentication
   */
  async connect(): Promise<void> {
    if (this.isConnecting) {
      console.log('[WebSocket] Connection already in progress');
      return;
    }

    if (this.stompClient?.connected) {
      console.log('[WebSocket] Already connected');
      return;
    }

    return new Promise((resolve, reject) => {
      const token = this.authService.getAccessToken();
      if (!token) {
        const error = new Error('No access token available');
        this.handleConnectionError(error);
        reject(error);
        return;
      }

      this.isConnecting = true;
      this.isManualDisconnect = false;
      this.connectionStatus$.next(WebSocketConnectionStatus.CONNECTING);

      try {
        // Create SockJS connection
        const socket = new SockJS(environment.chatWsUrl);

        // Create STOMP client with production-ready configuration
        this.stompClient = new Client({
          webSocketFactory: () => socket as any,
          connectHeaders: {
            Authorization: `Bearer ${token}`,
          },
          debug: (str) => {
            if (!environment.production) {
              console.log('[WebSocket Debug]:', str);
            }
          },
          reconnectDelay: 0, // We handle reconnection manually
          heartbeatIncoming: 10000, // 10 seconds
          heartbeatOutgoing: 10000, // 10 seconds
        });

        // Set up connection callbacks
        this.stompClient.onConnect = () => {
          console.log('[WebSocket] Connected successfully');
          this.isConnecting = false;
          this.reconnectionAttempts = 0;
          this.connectionStatus$.next(WebSocketConnectionStatus.CONNECTED);

          // Resubscribe to all previous subscriptions
          this.resubscribeAll();

          resolve();
        };

        this.stompClient.onStompError = (frame) => {
          console.error('[WebSocket] STOMP Error:', frame.headers['message']);
          console.error('[WebSocket] Details:', frame.body);
          this.isConnecting = false;
          this.connectionStatus$.next(WebSocketConnectionStatus.ERROR);

          const error = new Error(frame.headers['message'] || 'STOMP connection error');
          this.handleConnectionError(error);
          reject(error);
        };

        this.stompClient.onWebSocketClose = (event) => {
          console.log('[WebSocket] Connection closed:', event);
          this.isConnecting = false;

          if (!this.isManualDisconnect) {
            this.connectionStatus$.next(WebSocketConnectionStatus.DISCONNECTED);
            this.scheduleReconnection();
          } else {
            this.connectionStatus$.next(WebSocketConnectionStatus.DISCONNECTED);
          }
        };

        this.stompClient.onWebSocketError = (error) => {
          console.error('[WebSocket] WebSocket error:', error);
          this.isConnecting = false;
          this.connectionStatus$.next(WebSocketConnectionStatus.ERROR);
          this.handleConnectionError(error);
        };

        // Handle token refresh
        this.authService.accessToken$
          .pipe(
            filter(token => token !== this.authService.getAccessToken()),
            takeUntil(this.destroy$)
          )
          .subscribe(() => {
            console.log('[WebSocket] Token refreshed, reconnecting...');
            this.reconnectWithNewToken();
          });

        // Activate the connection
        this.stompClient.activate();
      } catch (error) {
        this.isConnecting = false;
        this.handleConnectionError(error);
        reject(error);
      }
    });
  }

  /**
   * Disconnect from WebSocket server
   */
  disconnect(isManual = false): void {
    this.isManualDisconnect = isManual;

    if (this.reconnectionTimer) {
      this.reconnectionTimer.unsubscribe();
      this.reconnectionTimer = null;
    }

    if (this.stompClient) {
      // Store subscription destinations for potential reconnection
      const destinations = Array.from(this.subscriptions.keys());

      // Unsubscribe from all topics
      this.subscriptions.forEach((subscription) => {
        try {
          subscription.unsubscribe();
        } catch (error) {
          console.warn('[WebSocket] Error unsubscribing:', error);
        }
      });
      this.subscriptions.clear();

      // Deactivate the client
      try {
        this.stompClient.deactivate();
      } catch (error) {
        console.warn('[WebSocket] Error deactivating client:', error);
      }

      this.stompClient = null;
      this.connectionStatus$.next(WebSocketConnectionStatus.DISCONNECTED);
      console.log('[WebSocket] Disconnected');
    }
  }

  /**
   * Subscribe to a destination (topic or queue)
   */
  subscribe(destination: string, callback: messageCallbackType): void {
    if (!this.stompClient?.connected) {
      console.warn('[WebSocket] Not connected. Will subscribe when connected:', destination);
      // Store for later subscription
      this.subscriptions.set(destination, { destination, callback } as any);
      return;
    }

    // Unsubscribe if already subscribed
    if (this.subscriptions.has(destination)) {
      try {
        this.subscriptions.get(destination)?.unsubscribe();
      } catch (error) {
        console.warn('[WebSocket] Error unsubscribing:', error);
      }
    }

    try {
      const subscription = this.stompClient.subscribe(destination, (message) => {
        try {
          const body = JSON.parse(message.body);
          this.messageSubject.next({ destination, body });
          callback(message);
        } catch (error) {
          console.error('[WebSocket] Error parsing message:', error);
        }
      });

      this.subscriptions.set(destination, subscription);
      console.log('[WebSocket] Subscribed to:', destination);
    } catch (error) {
      console.error('[WebSocket] Error subscribing to destination:', destination, error);
    }
  }

  /**
   * Unsubscribe from a destination
   */
  unsubscribe(destination: string): void {
    const subscription = this.subscriptions.get(destination);
    if (subscription) {
      try {
        subscription.unsubscribe();
      } catch (error) {
        console.warn('[WebSocket] Error unsubscribing:', error);
      }
      this.subscriptions.delete(destination);
      console.log('[WebSocket] Unsubscribed from:', destination);
    }
  }

  /**
   * Send a message to a destination
   */
  send(destination: string, body: any): void {
    if (!this.stompClient?.connected) {
      console.warn('[WebSocket] Not connected. Cannot send message to:', destination);
      this.toastr.warning('Connection lost. Please wait...', 'WebSocket');
      return;
    }

    try {
      this.stompClient.publish({
        destination,
        body: JSON.stringify(body),
      });

      console.log('[WebSocket] Sent message to:', destination);
    } catch (error) {
      console.error('[WebSocket] Error sending message:', error);
      this.toastr.error('Failed to send message', 'WebSocket');
    }
  }

  /**
   * Check if WebSocket is connected
   */
  isConnected(): boolean {
    return this.stompClient?.connected ?? false;
  }

  /**
   * Get messages for a specific destination
   */
  getMessagesForDestination(destination: string): Observable<any> {
    return this.messages$.pipe(
      filter((message) => message.destination === destination),
      filter((message) => message.body !== null)
    );
  }

  /**
   * Get current connection status
   */
  getConnectionStatus(): WebSocketConnectionStatus {
    return this.connectionStatus$.value;
  }

  /**
   * Schedule reconnection with exponential backoff
   */
  private scheduleReconnection(): void {
    if (this.isManualDisconnect) {
      return;
    }

    if (this.reconnectionAttempts >= this.reconnectionConfig.maxAttempts) {
      console.error('[WebSocket] Max reconnection attempts reached');
      this.toastr.error('Unable to connect to chat service', 'Connection Error');
      return;
    }

    const delay = Math.min(
      this.reconnectionConfig.initialDelay *
        Math.pow(this.reconnectionConfig.backoffMultiplier, this.reconnectionAttempts),
      this.reconnectionConfig.maxDelay
    );

    this.reconnectionAttempts++;
    this.connectionStatus$.next(WebSocketConnectionStatus.RECONNECTING);

    console.log(
      `[WebSocket] Scheduling reconnection attempt ${this.reconnectionAttempts}/${this.reconnectionConfig.maxAttempts} in ${delay}ms`
    );

    this.reconnectionTimer = timer(delay)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        console.log(`[WebSocket] Attempting reconnection ${this.reconnectionAttempts}/${this.reconnectionConfig.maxAttempts}`);
        this.connect().catch((error) => {
          console.error('[WebSocket] Reconnection failed:', error);
        });
      });
  }

  /**
   * Reconnect with new token
   */
  private async reconnectWithNewToken(): Promise<void> {
    this.disconnect(false);
    try {
      await this.connect();
    } catch (error) {
      console.error('[WebSocket] Failed to reconnect with new token:', error);
    }
  }

  /**
   * Resubscribe to all destinations after reconnection
   */
  private resubscribeAll(): void {
    if (!this.stompClient?.connected) {
      return;
    }

    const destinations = Array.from(this.subscriptions.keys());
    this.subscriptions.clear();

    destinations.forEach((destination) => {
      // Re-subscribe (callback was stored)
      const stored = (this.subscriptions.get(destination) as any)?.callback;
      if (stored) {
        this.subscribe(destination, stored);
      }
    });
  }

  /**
   * Handle connection errors
   */
  private handleConnectionError(error: any): void {
    console.error('[WebSocket] Connection error:', error);

    // Don't show error toast on first connection attempt
    if (this.reconnectionAttempts > 0) {
      const message = error?.message || 'Connection failed';
      this.toastr.warning(message, 'WebSocket Connection', {
        timeOut: 3000,
      });
    }
  }
}
