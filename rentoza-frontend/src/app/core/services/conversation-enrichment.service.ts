import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';
import { map, catchError } from 'rxjs/operators';
import { ConversationDTO } from '@core/models/chat.model';
import { BookingService } from './booking.service';
import { UserService } from './user.service';
import { UserBooking } from '@core/models/booking.model';
import { UserProfileDetails } from '@core/models/user.model';

/**
 * Service to enrich conversation DTOs with booking and user details
 * for improved UX in messaging interface
 */
@Injectable({
  providedIn: 'root',
})
export class ConversationEnrichmentService {
  private readonly bookingService = inject(BookingService);
  private readonly userService = inject(UserService);

  // Cache for booking and user data
  private bookingCache = new Map<string, UserBooking>();
  private userCache = new Map<string, { firstName: string; lastName: string }>();

  /**
   * Enrich a single conversation with booking and user details
   */
  enrichConversation(conversation: ConversationDTO): Observable<ConversationDTO> {
    // Check if already enriched
    if (conversation.carBrand && conversation.renterName && conversation.ownerName) {
      return of(conversation);
    }

    // Guard against null IDs - return conversation as-is with placeholder names
    if (!conversation.ownerId || !conversation.renterId) {
      console.warn(`[Enrichment] Conversation ${conversation.id} has null ownerId or renterId`);
      return of({
        ...conversation,
        renterName: conversation.renterName || 'Unknown User',
        ownerName: conversation.ownerName || 'Unknown User',
      });
    }

    // Fetch booking details and user names in parallel
    return forkJoin({
      booking: this.getBookingDetails(conversation.bookingId),
      renterName: this.getUserName(conversation.renterId),
      ownerName: this.getUserName(conversation.ownerId),
    }).pipe(
      map(({ booking, renterName, ownerName }) => ({
        ...conversation,
        carBrand: booking?.carBrand,
        carModel: booking?.carModel,
        carYear: booking?.carYear,
        startTime: booking?.startTime,
        endTime: booking?.endTime,
        renterName: renterName,
        ownerName: ownerName,
      })),
      catchError((error) => {
        console.error('[ConversationEnrichment] Error enriching conversation:', error);
        return of(conversation); // Return original on error
      }),
    );
  }

  /**
   * Enrich multiple conversations
   */
  enrichConversations(conversations: ConversationDTO[]): Observable<ConversationDTO[]> {
    if (!conversations || conversations.length === 0) {
      return of([]);
    }

    // Enrich all conversations in parallel
    const enriched$ = conversations.map((conv) => this.enrichConversation(conv));

    return forkJoin(enriched$).pipe(
      catchError((error) => {
        console.error('[ConversationEnrichment] Error enriching conversations:', error);
        return of(conversations); // Return original on error
      }),
    );
  }

  /**
   * Get booking details from cache or API
   */
  private getBookingDetails(bookingId: string): Observable<UserBooking | null> {
    // Check cache
    if (this.bookingCache.has(bookingId)) {
      return of(this.bookingCache.get(bookingId)!);
    }

    // Fetch from API using getBookingById which works for both Renter and Owner
    return this.bookingService.getBookingById(bookingId).pipe(
      map((booking: any) => {
        if (!booking) return null;

        // Map Booking to UserBooking structure expected by cache/enrichment
        // Note: Booking model has nested car/renter, UserBooking is flat
        // Using startTime/endTime for exact timestamp architecture
        const userBooking: UserBooking = {
          id: Number(booking.id),
          carId: Number(booking.car?.id),
          carBrand: booking.car?.brand || booking.car?.make || 'Unknown',
          carModel: booking.car?.model || 'Unknown',
          carYear: booking.car?.year || 0,
          carImageUrl: booking.car?.imageUrl || null,
          carLocation: '', // Not available in standard Booking DTO
          carPricePerDay: 0, // Not available in standard Booking DTO
          startTime: booking.startTime,
          endTime: booking.endTime,
          totalPrice: booking.totalPrice,
          status: booking.status,
          hasReview: false,
          reviewRating: null,
          reviewComment: null,
          insuranceType: 'BASIC', // Default
          prepaidRefuel: false,
        };

        this.bookingCache.set(bookingId, userBooking);
        return userBooking;
      }),
      catchError((err) => {
        console.error(`[Enrichment] Failed to fetch booking ${bookingId}`, err);
        return of(null);
      }),
    );
  }

  /**
   * Get user name from cache or derive from userId
   * For now, we'll use a simplified approach since we don't have a user details endpoint
   */
  private getUserName(userId: string): Observable<string> {
    // Check cache
    if (this.userCache.has(userId)) {
      const user = this.userCache.get(userId)!;
      return of(`${user.firstName} ${user.lastName}`);
    }

    // For now, return a placeholder
    // TODO: Implement proper user details endpoint or extract from JWT/auth state
    return of(`User ${userId.substring(0, 6)}`);
  }

  /**
   * Clear caches (useful for logout)
   */
  clearCache(): void {
    this.bookingCache.clear();
    this.userCache.clear();
  }
}
