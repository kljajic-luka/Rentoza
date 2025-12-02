import { ConversationDTO } from '@core/models/chat.model';

export interface ChatDisplayInfo {
  title: string;
  subtitle: string;
  statusLabel: string;
  statusColor: 'accent' | 'primary' | 'warn' | '';
  emptyStateText: string;
}

export class ChatUiHelper {
  /**
   * Generates role-aware display information for a conversation.
   *
   * @param conv The conversation DTO
   * @param currentUserId The ID of the current user
   * @returns ChatDisplayInfo containing localized title, subtitle, and status label
   */
  static getDisplayInfo(conv: ConversationDTO, currentUserId: string): ChatDisplayInfo {
    const isOwner = conv.ownerId === currentUserId;
    const tripStatus = this.getTripStatus(conv);
    const carInfo = this.getCarInfo(conv);

    let title = '';
    let subtitle = '';
    let statusLabel = '';
    let statusColor: 'accent' | 'primary' | 'warn' | '' = '';
    let emptyStateText = 'Započnite razgovor.';

    // 1. Determine Title & Subtitle based on Role and Trip Status
    if (isOwner) {
      // --- OWNER PERSPECTIVE (Vlasnik) ---
      emptyStateText = 'Sačekajte poruku od vozača ili im pišite prvi.';
      switch (tripStatus) {
        case 'future':
          title = `Buduće putovanje${carInfo ? ' • ' + carInfo : ''}`;
          subtitle = 'Rezervacija je potvrđena – možete kontaktirati vozača.';
          statusLabel = 'Buduće';
          statusColor = 'primary';
          break;
        case 'current':
          title = `Trenutno putovanje${carInfo ? ' • ' + carInfo : ''}`;
          subtitle = 'Vaše vozilo je trenutno iznajmljeno – ostanite u kontaktu sa vozačem.';
          statusLabel = 'Trenutno';
          statusColor = 'accent';
          break;
        case 'past':
          title = `Završeno putovanje${carInfo ? ' • ' + carInfo : ''}`;
          subtitle = 'Putovanje je završeno – možete ostaviti ocenu vozaču.';
          statusLabel = 'Prošlo';
          statusColor = 'warn';
          break;
        default:
          // Fallback for other statuses
          if (conv.status === 'PENDING') {
            title = 'Novi zahtev za rezervaciju';
            subtitle = 'Vozač je poslao zahtev – odlučite da li ćete ga prihvatiti.';
            statusLabel = 'Na čekanju';
            emptyStateText = 'Možete kontaktirati vozača pre prihvatanja zahteva.';
          } else if (conv.status === 'CLOSED') {
            title = 'Arhiviran razgovor';
            subtitle = 'Ova rezervacija je završena ili otkazana.';
            statusLabel = 'Završeno';
            emptyStateText = 'Razgovor je završen.';
          } else {
            title = 'Razgovor';
            subtitle = 'Komunikacija sa vozačem.';
            statusLabel = 'Aktivno';
            statusColor = 'accent';
          }
          break;
      }
    } else {
      // --- RENTER PERSPECTIVE (Vozač) ---
      emptyStateText = 'Pošaljite poruku vlasniku za dogovor oko preuzimanja.';
      switch (tripStatus) {
        case 'future':
          title = `Buduće putovanje${carInfo ? ' • ' + carInfo : ''}`;
          subtitle = 'Rezervacija je potvrđena – možete kontaktirati vlasnika.';
          statusLabel = 'Buduće';
          statusColor = 'primary';
          break;
        case 'current':
          title = `Trenutno putovanje${carInfo ? ' • ' + carInfo : ''}`;
          subtitle = 'Vaša vožnja je u toku – možete komunicirati sa vlasnikom.';
          statusLabel = 'Trenutno';
          statusColor = 'accent';
          break;
        case 'past':
          title = `Završeno putovanje${carInfo ? ' • ' + carInfo : ''}`;
          subtitle = 'Putovanje je završeno – možete ostaviti ocenu.';
          statusLabel = 'Prošlo';
          statusColor = 'warn';
          break;
        default:
          // Fallback for other statuses
          if (conv.status === 'PENDING') {
            title = 'Zahtev za rezervaciju';
            subtitle = 'Zahtev je poslat – čekamo odgovor vlasnika.';
            statusLabel = 'Na čekanju';
            emptyStateText = 'Možete pisati vlasniku dok čekate potvrdu.';
          } else if (conv.status === 'CLOSED') {
            title = 'Arhiviran razgovor';
            subtitle = 'Ova rezervacija je završena ili otkazana.';
            statusLabel = 'Završeno';
            emptyStateText = 'Razgovor je završen.';
          } else {
            title = 'Razgovor';
            subtitle = 'Komunikacija sa vlasnikom.';
            statusLabel = 'Aktivno';
            statusColor = 'accent';
          }
          break;
      }
    }

    // Handle unavailable/cancelled specifically if needed, or rely on tripStatus 'unavailable'
    if (tripStatus === 'unavailable') {
      title = 'Nedostupno putovanje';
      subtitle = 'Informacije o rezervaciji nisu dostupne.';
      statusLabel = 'Nedostupno';
      statusColor = 'warn';
      emptyStateText = 'Nema dostupnih informacija.';
    }

    return { title, subtitle, statusLabel, statusColor, emptyStateText };
  }

  /**
   * Helper to determine trip status (current, future, past)
   */
  private static getTripStatus(
    conv: ConversationDTO
  ): 'current' | 'future' | 'past' | 'unknown' | 'unavailable' {
    if (conv.tripStatus) {
      const status = conv.tripStatus.toLowerCase();
      if (['current', 'future', 'past', 'unavailable'].includes(status)) {
        return status as any;
      }
    }

    // Using startTime/endTime for exact timestamp architecture
    if (!conv.startTime || !conv.endTime) {
      return 'unknown';
    }

    const now = new Date();
    const startTime = new Date(conv.startTime);
    const endTime = new Date(conv.endTime);

    if (now < startTime) {
      return 'future';
    } else if (now > endTime) {
      return 'past';
    } else {
      return 'current';
    }
  }

  /**
   * Helper to format car info string
   */
  private static getCarInfo(conv: ConversationDTO): string {
    if (
      !conv.carBrand ||
      !conv.carModel ||
      conv.carBrand === 'Unknown' ||
      conv.carModel === 'Unknown'
    ) {
      return '';
    }
    const year = conv.carYear && conv.carYear > 0 ? `${conv.carYear} ` : '';
    return `${year}${conv.carBrand} ${conv.carModel}`;
  }
}
