import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { onCLS, onLCP, onFCP, onTTFB, onINP, Metric } from 'web-vitals';
import { environment } from '@environments/environment';

export interface WebVitalsMetric {
  name: string;
  value: number;
  rating: 'good' | 'needs-improvement' | 'poor';
  delta: number;
  id: string;
  navigationType: string;
  timestamp: number;
  url: string;
  userAgent: string;
}

@Injectable({
  providedIn: 'root',
})
export class PerformanceMonitoringService {
  private http = inject(HttpClient);
  private metricsQueue: WebVitalsMetric[] = [];
  private flushInterval = 30000; // 30 seconds
  private maxQueueSize = 10;

  constructor() {
    if (!environment.production) {
      console.log('🔍 Performance monitoring initialized (dev mode)');
    }
  }

  /**
   * Initialize Web Vitals monitoring
   * Call this once in main.ts or app component
   */
  initMonitoring(): void {
    // Cumulative Layout Shift - visual stability
    onCLS(this.sendMetric.bind(this), { reportAllChanges: false });

    // Interaction to Next Paint - responsiveness (replaces FID)
    onINP(this.sendMetric.bind(this), { reportAllChanges: false });

    // Largest Contentful Paint - loading performance
    onLCP(this.sendMetric.bind(this), { reportAllChanges: false });

    // First Contentful Paint - initial render
    onFCP(this.sendMetric.bind(this), { reportAllChanges: false });

    // Time to First Byte - server response time
    onTTFB(this.sendMetric.bind(this), { reportAllChanges: false });

    // Flush queue periodically
    setInterval(() => this.flushMetrics(), this.flushInterval);

    // Flush on page unload
    if (typeof window !== 'undefined') {
      window.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') {
          this.flushMetrics(true);
        }
      });
    }
  }

  private sendMetric(metric: Metric): void {
    const webVitalsMetric: WebVitalsMetric = {
      name: metric.name,
      value: metric.value,
      rating: metric.rating,
      delta: metric.delta,
      id: metric.id,
      navigationType: metric.navigationType,
      timestamp: Date.now(),
      url: window.location.pathname,
      userAgent: navigator.userAgent,
    };

    // Log in development
    if (!environment.production) {
      console.log(`📊 ${metric.name}:`, {
        value: `${Math.round(metric.value)}${metric.name === 'CLS' ? '' : 'ms'}`,
        rating: metric.rating,
      });
    }

    // Add to queue
    this.metricsQueue.push(webVitalsMetric);

    // Flush if queue is full
    if (this.metricsQueue.length >= this.maxQueueSize) {
      this.flushMetrics();
    }
  }

  private flushMetrics(useBeacon = false): void {
    if (this.metricsQueue.length === 0) return;

    const metrics = [...this.metricsQueue];
    this.metricsQueue = [];

    if (environment.production) {
      const endpoint = `${environment.baseApiUrl}/metrics/web-vitals`;

      if (useBeacon && navigator.sendBeacon) {
        // Use sendBeacon for page unload - doesn't wait for response
        const blob = new Blob([JSON.stringify(metrics)], { type: 'application/json' });
        navigator.sendBeacon(endpoint, blob);
      } else {
        // Regular HTTP POST for periodic flushes
        this.http.post(endpoint, metrics).subscribe({
          error: (err) => console.error('Failed to send web vitals metrics', err),
        });
      }
    }
  }

  /**
   * Manual performance mark for custom timing
   */
  mark(name: string): void {
    if (typeof performance !== 'undefined') {
      performance.mark(name);
    }
  }

  /**
   * Measure time between two marks
   */
  measure(name: string, startMark: string, endMark?: string): number | null {
    if (typeof performance === 'undefined') return null;

    try {
      const measure = performance.measure(name, startMark, endMark);

      if (!environment.production) {
        console.log(`⏱️  ${name}: ${Math.round(measure.duration)}ms`);
      }

      return measure.duration;
    } catch (error) {
      console.error('Performance measurement failed', error);
      return null;
    }
  }

  /**
   * Get current memory usage (Chrome only)
   */
  getMemoryUsage(): any {
    if ('memory' in performance) {
      return (performance as any).memory;
    }
    return null;
  }
}
