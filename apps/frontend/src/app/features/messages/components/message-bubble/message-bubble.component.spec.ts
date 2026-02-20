import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { MessageBubbleComponent } from './message-bubble.component';
import { MessageDTO } from '@core/models/chat.model';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeMessage(overrides: Partial<MessageDTO> = {}): MessageDTO {
  return {
    id: 1,
    conversationId: 10,
    senderId: 'user-a',
    senderName: 'Ana',
    content: 'Hello',
    timestamp: '2024-01-01T12:00:00Z',
    readBy: [],
    isRead: false,
    ...overrides,
  };
}

async function createComponent(message: MessageDTO, isOwn = false) {
  await TestBed.configureTestingModule({
    imports: [MessageBubbleComponent, NoopAnimationsModule],
  }).compileComponents();

  const fixture: ComponentFixture<MessageBubbleComponent> =
    TestBed.createComponent(MessageBubbleComponent);
  fixture.componentRef.setInput('message', message);
  fixture.componentRef.setInput('isOwn', isOwn);
  fixture.detectChanges();
  return fixture;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('MessageBubbleComponent', () => {
  it('should render message text content', async () => {
    const fixture = await createComponent(makeMessage({ content: 'Test message' }));
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.content')?.textContent?.trim()).toBe('Test message');
  });

  it('should NOT render attachment section when mediaUrl is absent', async () => {
    const fixture = await createComponent(makeMessage({ mediaUrl: undefined }));
    expect(fixture.nativeElement.querySelector('.attachment')).toBeNull();
  });

  describe('image attachment', () => {
    const imageMediaUrl = '/api/attachments/booking-7/photo.jpg';
    const expectedSrc = 'https://chat.rentoza.rs/api/attachments/booking-7/photo.jpg';

    it('should render <img> with fully-resolved URL (not raw relative URL)', async () => {
      const fixture = await createComponent(makeMessage({ mediaUrl: imageMediaUrl }));
      const img = fixture.debugElement.query(By.css('.attachment-image'));
      expect(img).toBeTruthy('expected <img> to be present');
      expect(img.nativeElement.getAttribute('src')).toBe(expectedSrc);
    });

    it('should wrap image in an <a> with fully-resolved href', async () => {
      const fixture = await createComponent(makeMessage({ mediaUrl: imageMediaUrl }));
      const anchor = fixture.debugElement.query(By.css('.image-attachment'));
      expect(anchor).toBeTruthy('expected image anchor to be present');
      expect(anchor.nativeElement.getAttribute('href')).toBe(expectedSrc);
    });

    it('should NOT set raw relative URL as src or href on any element', async () => {
      const fixture = await createComponent(makeMessage({ mediaUrl: imageMediaUrl }));
      const el: HTMLElement = fixture.nativeElement;
      // Attribute selectors match the exact attribute value — the raw relative path
      // must not appear as a standalone src/href; only the absolute resolved URL is valid.
      expect(el.querySelector(`img[src="${imageMediaUrl}"]`)).toBeNull(
        'img.src must not be the raw relative path',
      );
      expect(el.querySelector(`a[href="${imageMediaUrl}"]`)).toBeNull(
        'anchor href must not be the raw relative path',
      );
    });

    it('should show fallback CTA and hide <img> when image fails to load', async () => {
      const fixture = await createComponent(makeMessage({ mediaUrl: imageMediaUrl }));

      // Simulate browser image load error
      const img = fixture.debugElement.query(By.css('.attachment-image'));
      img.nativeElement.dispatchEvent(new Event('error'));
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.attachment-image'))).toBeNull(
        'img should be gone after error',
      );
      const fallback = fixture.debugElement.query(By.css('.file-attachment'));
      expect(fallback).toBeTruthy('fallback CTA should appear after error');
      expect(fallback.nativeElement.getAttribute('href')).toBe(expectedSrc);
    });
  });

  describe('PDF / file attachment', () => {
    const pdfMediaUrl = '/api/attachments/booking-7/contract.pdf';
    const expectedHref = 'https://chat.rentoza.rs/api/attachments/booking-7/contract.pdf';

    it('should render file-attachment <a> (not an <img>) for PDF', async () => {
      const fixture = await createComponent(makeMessage({ mediaUrl: pdfMediaUrl }));
      expect(fixture.debugElement.query(By.css('.attachment-image'))).toBeNull();
      const anchor = fixture.debugElement.query(By.css('.file-attachment'));
      expect(anchor).toBeTruthy();
      expect(anchor.nativeElement.getAttribute('href')).toBe(expectedHref);
    });
  });

  describe('unknown / unresolvable mediaUrl', () => {
    it('should hide the attachment section for an unrecognised relative path', async () => {
      const fixture = await createComponent(makeMessage({ mediaUrl: '/uploads/profile.jpg' }));
      // resolveAttachmentUrl returns null → resolvedMediaUrl() is null → *ngIf hides section
      expect(fixture.nativeElement.querySelector('.attachment')).toBeNull();
    });
  });
});
