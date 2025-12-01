/**
 * Lazy Image Directive
 *
 * Defers image loading until the element is within proximity of the viewport.
 * Uses IntersectionObserver API for efficient, non-blocking detection.
 *
 * ## Usage
 * ```html
 * <!-- Basic usage -->
 * <img appLazyImg [lazySrc]="imageUrl" alt="Photo" />
 *
 * <!-- With skeleton while loading -->
 * <div class="photo-wrapper">
 *   <app-photo-skeleton *ngIf="!imageLoaded"></app-photo-skeleton>
 *   <img
 *     appLazyImg
 *     [lazySrc]="imageUrl"
 *     (lazyLoad)="imageLoaded = true"
 *     [class.loaded]="imageLoaded"
 *   />
 * </div>
 * ```
 *
 * ## Performance Impact
 * - Memory: Prevents loading 50MB of photos when user only views 2
 * - Bandwidth: Only downloads images as user scrolls
 * - Battery: Reduces network activity on mobile
 *
 * ## Browser Support
 * - IntersectionObserver: Chrome 51+, Firefox 55+, Safari 12.1+
 * - Fallback: Immediate load for unsupported browsers
 */
import {
  Directive,
  ElementRef,
  Input,
  Output,
  EventEmitter,
  OnInit,
  OnDestroy,
  inject,
  PLATFORM_ID,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

@Directive({
  selector: 'img[appLazyImg]',
  standalone: true,
})
export class LazyImgDirective implements OnInit, OnDestroy {
  private readonly elementRef = inject(ElementRef<HTMLImageElement>);
  private readonly platformId = inject(PLATFORM_ID);

  private observer: IntersectionObserver | null = null;
  private hasLoaded = false;

  /**
   * The image URL to load when element enters viewport.
   */
  @Input() lazySrc: string = '';

  /**
   * Placeholder image to show before lazy loading.
   * Default: transparent 1x1 pixel.
   */
  @Input() placeholder: string =
    'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';

  /**
   * Root margin for IntersectionObserver.
   * Positive values load images before they enter viewport.
   * Default: 100px (preload when within 100px of viewport)
   */
  @Input() rootMargin: string = '100px';

  /**
   * Threshold for intersection.
   * 0 = trigger when any part is visible, 1 = trigger when fully visible.
   */
  @Input() threshold: number = 0;

  /**
   * Emitted when the image starts loading (enters viewport).
   */
  @Output() lazyLoad = new EventEmitter<void>();

  /**
   * Emitted when the image has fully loaded.
   */
  @Output() lazyLoaded = new EventEmitter<void>();

  /**
   * Emitted if the image fails to load.
   */
  @Output() lazyError = new EventEmitter<ErrorEvent>();

  ngOnInit(): void {
    // SSR guard
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const img = this.elementRef.nativeElement;

    // Set placeholder immediately
    img.src = this.placeholder;

    // Check for IntersectionObserver support
    if ('IntersectionObserver' in window) {
      this.initializeObserver();
    } else {
      // Fallback: load immediately for unsupported browsers
      this.loadImage();
    }
  }

  ngOnDestroy(): void {
    this.disconnectObserver();
  }

  /**
   * Initialize the IntersectionObserver.
   */
  private initializeObserver(): void {
    const options: IntersectionObserverInit = {
      root: null, // viewport
      rootMargin: this.rootMargin,
      threshold: this.threshold,
    };

    this.observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting && !this.hasLoaded) {
          this.loadImage();
          this.disconnectObserver();
        }
      });
    }, options);

    this.observer.observe(this.elementRef.nativeElement);
  }

  /**
   * Disconnect and cleanup the observer.
   */
  private disconnectObserver(): void {
    if (this.observer) {
      this.observer.disconnect();
      this.observer = null;
    }
  }

  /**
   * Load the actual image.
   */
  private loadImage(): void {
    if (this.hasLoaded || !this.lazySrc) {
      return;
    }

    this.hasLoaded = true;
    const img = this.elementRef.nativeElement;

    // Emit load start event
    this.lazyLoad.emit();

    // Set up load/error handlers
    img.onload = () => {
      img.classList.add('lazy-loaded');
      this.lazyLoaded.emit();
    };

    img.onerror = (event: Event) => {
      img.classList.add('lazy-error');
      this.lazyError.emit(event as ErrorEvent);
    };

    // Trigger the load
    img.src = this.lazySrc;
  }

  /**
   * Force immediate load (useful for programmatic control).
   */
  public forceLoad(): void {
    this.disconnectObserver();
    this.loadImage();
  }
}
