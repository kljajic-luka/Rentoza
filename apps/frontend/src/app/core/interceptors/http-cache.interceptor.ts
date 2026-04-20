import { HttpInterceptorFn, HttpResponse, HttpErrorResponse } from '@angular/common/http';
import { tap, catchError, of, throwError } from 'rxjs';

/**
 * HTTP Cache Store
 * Singleton cache for HTTP responses with ETag support
 */
class HttpCacheStore {
  private cache = new Map<
    string,
    { response: HttpResponse<any>; etag: string | null; timestamp: number }
  >();
  private readonly MAX_CACHE_SIZE = 50;
  private readonly CACHE_DURATION_MS = 5 * 60 * 1000; // 5 minutes

  get(key: string): { response: HttpResponse<any>; etag: string | null } | null {
    const cached = this.cache.get(key);
    if (!cached) return null;

    // Check if cache entry is expired
    if (Date.now() - cached.timestamp > this.CACHE_DURATION_MS) {
      this.cache.delete(key);
      return null;
    }

    return { response: cached.response, etag: cached.etag };
  }

  set(key: string, response: HttpResponse<any>, etag: string | null): void {
    // Implement LRU by removing oldest entry when full
    if (this.cache.size >= this.MAX_CACHE_SIZE) {
      const firstKey = this.cache.keys().next().value;
      if (firstKey) {
        this.cache.delete(firstKey);
      }
    }

    this.cache.set(key, {
      response: response.clone(),
      etag,
      timestamp: Date.now(),
    });
  }

  clear(): void {
    this.cache.clear();
  }

  clearByPattern(pattern: string): void {
    const keysToDelete: string[] = [];
    this.cache.forEach((value, key) => {
      if (key.includes(pattern)) {
        keysToDelete.push(key);
      }
    });
    keysToDelete.forEach((key) => this.cache.delete(key));
  }
}

// Singleton cache instance
const cacheStore = new HttpCacheStore();

/**
 * HTTP Cache Interceptor (Functional)
 * Implements client-side HTTP caching with ETag support
 */
export const httpCacheInterceptor: HttpInterceptorFn = (req, next) => {
  // Only cache GET requests
  if (req.method !== 'GET') {
    return next(req);
  }

  // Check if request should be cached
  if (!shouldCache(req.url)) {
    return next(req);
  }

  const cacheKey = `${req.method}:${req.urlWithParams}`;
  const cachedResponse = cacheStore.get(cacheKey);

  // If we have a cached response with ETag, add If-None-Match header
  if (cachedResponse?.etag) {
    const clonedReq = req.clone({
      setHeaders: {
        'If-None-Match': cachedResponse.etag,
      },
    });

    return next(clonedReq).pipe(
      tap((event) => {
        if (event instanceof HttpResponse) {
          // Update cache with new response
          const etag = event.headers.get('ETag');
          cacheStore.set(cacheKey, event, etag);
        }
      }),
      // Handle 304 Not Modified - return cached response
      catchError((error: HttpErrorResponse) => {
        if (error.status === 304 && cachedResponse?.response) {
          // 304 means cache is still valid - return cached response
          console.debug('[HttpCache] 304 Not Modified - using cached response for:', req.url);
          return of(cachedResponse.response);
        }
        // Re-throw other errors
        return throwError(() => error);
      })
    );
  }

  // No ETag - check if we have a fresh cached response
  if (cachedResponse?.response) {
    return next(req).pipe(
      tap((event) => {
        if (event instanceof HttpResponse) {
          const etag = event.headers.get('ETag');
          cacheStore.set(cacheKey, event, etag);
        }
      })
    );
  }

  // No cache hit - make the request and cache the response
  return next(req).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) {
        const etag = event.headers.get('ETag');
        cacheStore.set(cacheKey, event, etag);
      }
    })
  );
};

/**
 * Determine if a request should be cached
 */
function shouldCache(url: string): boolean {
  // Cache car search, car details, and other read-only endpoints
  const cacheableUrls = ['/api/cars/search', '/api/cars/', '/api/reviews'];

  return cacheableUrls.some((cacheable) => url.includes(cacheable));
}

/**
 * Export cache store for manual cache management
 */
export function clearHttpCache(): void {
  cacheStore.clear();
}

export function clearHttpCacheByPattern(pattern: string): void {
  cacheStore.clearByPattern(pattern);
}