import { Component, input, computed, signal } from '@angular/core';

export interface ResponsiveImage {
  src: string;
  alt: string;
  width?: number;
  height?: number;
  sizes?: string;
  loading?: 'lazy' | 'eager';
  priority?: boolean;
}

@Component({
  selector: 'app-optimized-image',
  standalone: true,
  template: `
    <picture>
      <!-- WebP format for modern browsers -->
      @if (webpSrc()) {
      <source [srcset]="webpSrcset()" [sizes]="sizes()" type="image/webp" />
      }

      <!-- Fallback to original format -->
      <img
        [src]="src()"
        [srcset]="srcset()"
        [sizes]="sizes()"
        [alt]="alt()"
        [width]="width()"
        [height]="height()"
        [loading]="loading()"
        [class]="imageClass()"
        [style.background-image]="blurPlaceholder()"
        (load)="onImageLoad()"
        (error)="onImageError()"
      />
    </picture>
  `,
  styles: [
    `
      :host {
        display: block;
        position: relative;
        overflow: hidden;
      }

      picture {
        display: block;
        width: 100%;
        height: 100%;
      }

      img {
        display: block;
        width: 100%;
        height: auto;
        transition: opacity 0.3s ease-in-out;
      }

      img.loading {
        opacity: 0;
      }

      img.loaded {
        opacity: 1;
      }

      img.error {
        opacity: 0.5;
        filter: grayscale(100%);
      }
    `,
  ],
})
export class OptimizedImageComponent {
  // Inputs
  src = input.required<string>();
  alt = input.required<string>();
  width = input<number>();
  height = input<number>();
  sizes = input<string>('100vw');
  loading = input<'lazy' | 'eager'>('lazy');
  priority = input<boolean>(false);

  // Internal state
  isLoaded = signal(false);
  hasError = signal(false);

  // Computed values
  imageClass = computed(() => {
    if (this.hasError()) return 'error';
    if (this.isLoaded()) return 'loaded';
    return 'loading';
  });

  // Generate responsive srcset
  srcset = computed(() => {
    const baseSrc = this.src();
    if (!baseSrc) return '';

    // If it's an external URL (Unsplash, etc.), use their resize parameters
    if (baseSrc.includes('unsplash.com')) {
      return this.generateUnsplashSrcset(baseSrc);
    }

    // For local images, generate multiple sizes
    return this.generateLocalSrcset(baseSrc);
  });

  // Generate WebP version if supported
  webpSrc = computed(() => {
    const baseSrc = this.src();
    if (!baseSrc) return '';

    // Only for local images
    if (baseSrc.startsWith('http')) return '';

    return baseSrc.replace(/\.(jpg|jpeg|png)$/i, '.webp');
  });

  webpSrcset = computed(() => {
    const baseSrc = this.webpSrc();
    if (!baseSrc) return '';

    return this.generateLocalSrcset(baseSrc);
  });

  // Low-quality image placeholder (LQIP)
  blurPlaceholder = computed(() => {
    if (this.isLoaded()) return '';

    const baseSrc = this.src();
    if (baseSrc.includes('unsplash.com')) {
      // Use Unsplash's tiny thumbnail for blur
      return `url("${baseSrc}?w=20&q=10&blur=50")`;
    }

    return '';
  });

  private generateUnsplashSrcset(url: string): string {
    const widths = [640, 750, 828, 1080, 1200, 1920, 2048];
    return widths.map((w) => `${url}?w=${w}&q=80&fm=webp ${w}w`).join(', ');
  }

  private generateLocalSrcset(url: string): string {
    // For local images, you would typically generate these at build time
    // or use an image CDN. For now, just return the original.
    const widths = [640, 750, 828, 1080, 1200];
    return widths.map((w) => `${url}?w=${w} ${w}w`).join(', ');
  }

  onImageLoad(): void {
    this.isLoaded.set(true);
  }

  onImageError(): void {
    this.hasError.set(true);
    console.error('Failed to load image:', this.src());
  }
}