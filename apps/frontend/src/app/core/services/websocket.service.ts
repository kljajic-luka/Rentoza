import { Injectable, inject, OnDestroy } from '@angular/core';
// TYPE-ONLY imports: erased at compile-time, zero runtime cost.
// Actual stomp + sockjs modules are loaded dynamically inside connect()
// so they never appear in the initial bundle.
import type { Client, StompSubscription, messageCallbackType } from '@stomp/stompjs';
import { BehaviorSubject, Observable, Subject, Subscription, timer, firstValueFrom } from 'rxjs';
import { distinctUntilChanged, filter, takeUntil, take, timeout } from 'rxjs/operators';
import { environment } from '@environments/environment';
import { AuthService } from '@core/auth/auth.service';
import { UserProfile } from '@core/models/user.model';
import { ToastService } from './toast.service';

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
  private readonly toast = inject(ToastService);

  private stompClient: Client | null = null;
  private connectionStatus$ = new BehaviorSubject<WebSocketConnectionStatus>(
    WebSocketConnectionStatus.DISCONNECTED,
  );
  private subscriptions = new Map<string, StompSubscription>();
  private subscriptionCallbacks = new Map<string, messageCallbackType>();
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
      return;
    }

    if (this.stompClient?.connected) {
      return;
    }

    if (environment.auth?.useCookies) {
      try {
        await this.awaitAuthenticatedSession();
      } catch (error) {
        this.handleConnectionError(error);
        throw error;
      }
    }

    // Install Node.js globals once — required by sockjs-client and stompjs.
    // We use ??= so we never overwrite values set by other code.
    (window as any).global ??= window;
    (window as any).process ??= {
      env: { DEBUG: undefined },
      version: '',
      nextTick: (fn: (...args: unknown[]) => void) => setTimeout(fn, 0),
    };
    (window as any).Buffer ??= { isBuffer: () => false };

    // Dynamically import stompjs + sockjs-client — they are NOT in the initial
    // bundle. The bundler splits them into a separate lazy chunk that is only
    // fetched the first time connect() is called (messages page).
    const [{ Client: StompClient }, { default: SockJS }] = await Promise.all([
      import('@stomp/stompjs'),
      import('sockjs-client'),
    ]);

    return new Promise((resolve, reject) => {
      const token = this.authService.getAccessToken();
      if (!environment.auth?.useCookies && !token) {
        const error = new Error('No access token available');
        this.handleConnectionError(error);
        reject(error);
        return;
      }

      this.isConnecting = true;
      this.isManualDisconnect = false;
      this.connectionStatus$.next(WebSocketConnectionStatus.CONNECTING);

      try {
        // Create SockJS connection (SockJS + StompClient loaded via dynamic import above)
        const socket = new SockJS(environment.chatWsUrl);

        // Build connect headers based on auth mode
        const connectHeaders: Record<string, string> = {};

        if (!environment.auth?.useCookies && token) {
          connectHeaders['Authorization'] = `Bearer ${token}`;
        }
        // In cookie mode, browser automatically sends cookies during WebSocket handshake

        // Create STOMP client with production-ready configuration
        this.stompClient = new StompClient({
          webSocketFactory: () => socket as any,
          connectHeaders, // Empty object when using cookies, contains Authorization when using headers
          debug: () => {
            // Debug logging disabled
          },
          reconnectDelay: 0, // We handle reconnection manually
          heartbeatIncoming: 10000, // 10 seconds
          heartbeatOutgoing: 10000, // 10 seconds
        });

        // Set up connection callbacks
        this.stompClient.onConnect = () => {
          this.isConnecting = false;
          this.reconnectionAttempts = 0;
          this.connectionStatus$.next(WebSocketConnectionStatus.CONNECTED);

          // Resubscribe to all previous subscriptions
          this.resubscribeAll();

          resolve();
        };

        this.stompClient.onStompError = (frame) => {
          this.isConnecting = false;
          this.connectionStatus$.next(WebSocketConnectionStatus.ERROR);

          const error = new Error(frame.headers['message'] || 'STOMP connection error');
          this.handleConnectionError(error);
          reject(error);
        };

        this.stompClient.onWebSocketClose = () => {
          this.isConnecting = false;

          if (!this.isManualDisconnect) {
            this.connectionStatus$.next(WebSocketConnectionStatus.DISCONNECTED);
            this.scheduleReconnection();
          } else {
            this.connectionStatus$.next(WebSocketConnectionStatus.DISCONNECTED);
          }
        };

        this.stompClient.onWebSocketError = (error) => {
          this.isConnecting = false;
          this.connectionStatus$.next(WebSocketConnectionStatus.ERROR);
          this.handleConnectionError(error);
        };

        // ✅ Handle token refresh (reconnect with new token)
        this.authService.accessToken$
          .pipe(
            filter((value): value is string => typeof value === 'string' && value.length > 0),
            distinctUntilChanged(),
            takeUntil(this.destroy$),
          )
          .subscribe(() => {
            this.reconnectWithNewToken();
          });

        // ✅ Handle session expiration (disconnect immediately on logout)
        this.authService.currentUser$
          .pipe(
            filter((user) => user === null), // User logged out or session expired
            takeUntil(this.destroy$),
          )
          .subscribe(() => {
            console.log('🔌 User logged out - disconnecting WebSocket');
            this.disconnect(true);
          });

        // ✅ Handle session expiration events
        this.authService.sessionExpired$.pipe(takeUntil(this.destroy$)).subscribe(() => {
          console.log('⏰ Session expired - disconnecting WebSocket');
          this.disconnect(true);
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
          // Silent error handling
        }
      });
      this.subscriptions.clear();

      // Deactivate the client
      try {
        this.stompClient.deactivate();
      } catch (error) {
        // Silent error handling
      }

      this.stompClient = null;
      this.connectionStatus$.next(WebSocketConnectionStatus.DISCONNECTED);
    }

    if (isManual) {
      this.subscriptionCallbacks.clear();
    }
  }

  /**
   * Subscribe to a destination (topic or queue)
   */
  subscribe(destination: string, callback: messageCallbackType): void {
    this.subscriptionCallbacks.set(destination, callback);

    if (!this.stompClient?.connected) {
      return;
    }

    if (this.subscriptions.has(destination)) {
      try {
        this.subscriptions.get(destination)?.unsubscribe();
      } catch (error) {
        // Silent error handling
      }
    }

    try {
      const subscription = this.stompClient.subscribe(destination, (message) => {
        try {
          const body = JSON.parse(message.body);
          this.messageSubject.next({ destination, body });
          callback(message);
        } catch (error) {
          // Silent error handling
        }
      });

      this.subscriptions.set(destination, subscription);
    } catch (error) {
      // Silent error handling
    }
  }

  /**
   * Unsubscribe from a destination
   */
  unsubscribe(destination: string): void {
    this.subscriptionCallbacks.delete(destination);
    const subscription = this.subscriptions.get(destination);
    if (subscription) {
      try {
        subscription.unsubscribe();
      } catch (error) {
        // Silent error handling
      }
      this.subscriptions.delete(destination);
    }
  }

  /**
   * Send a message to a destination
   */
  send(destination: string, body: any): void {
    if (!this.stompClient?.connected) {
      this.toast.warning('Veza izgubljena. Molimo sačekajte...');
      return;
    }

    try {
      this.stompClient.publish({
        destination,
        body: JSON.stringify(body),
      });
    } catch (error) {
      this.toast.error('Poruka nije poslata. Pokušajte ponovo.');
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
      filter((message) => message.body !== null),
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
      this.toast.serverError();
      return;
    }

    const delay = Math.min(
      this.reconnectionConfig.initialDelay *
        Math.pow(this.reconnectionConfig.backoffMultiplier, this.reconnectionAttempts),
      this.reconnectionConfig.maxDelay,
    );

    this.reconnectionAttempts++;
    this.connectionStatus$.next(WebSocketConnectionStatus.RECONNECTING);

    this.reconnectionTimer = timer(delay)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        this.connect().catch(() => {
          // Silent error handling
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
      // Silent error handling
    }
  }

  /**
   * Resubscribe to all destinations after reconnection
   */
  private resubscribeAll(): void {
    if (!this.stompClient?.connected) {
      return;
    }

    const callbacks = new Map(this.subscriptionCallbacks);
    this.subscriptions.clear();

    callbacks.forEach((callback, destination) => {
      this.subscribe(destination, callback);
    });
  }

  private async awaitAuthenticatedSession(): Promise<void> {
    if (this.authService.getCurrentUser()) {
      return;
    }

    await firstValueFrom(
      this.authService.currentUser$.pipe(
        filter((user): user is UserProfile => !!user),
        take(1),
        timeout({ each: 10000 }),
      ),
    );
  }

  /**
   * Handle connection errors
   */
  private handleConnectionError(error: any): void {
    // Don't show error toast on first connection attempt - silent reconnection
    if (this.reconnectionAttempts > 0) {
      // Silent reconnection - no toast needed
      console.warn('WebSocket reconnection attempt failed:', error?.message || 'Unknown error');
    }
  }
}