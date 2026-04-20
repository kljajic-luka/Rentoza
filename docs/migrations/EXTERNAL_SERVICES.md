# External Services Configuration
## OSRM Routing + Mapbox Frontend

---

## 1. OSRM (Open Street Map Routing Machine)

### Overview

OSRM provides free, open-source routing for accurate driving distances (vs. air-distance Haversine).

**Why OSRM instead of alternatives:**
- ✅ **Free & Open Source** - No API costs, self-hostable
- ✅ **Accurate for Serbia** - Uses OSM (OpenStreetMap) data which has good coverage
- ✅ **Fast** - Serbia routes complete in <1 second
- ✅ **No Authentication Required** - Public API is free to use

**Alternatives considered:**
- ❌ **Google Maps Directions API** - $5/1000 requests (expensive for Rentoza scale)
- ❌ **Mapbox Directions API** - $0.60/1000 requests (better than Google, but costs money)
- ✅ **OSRM** - Free + open source + self-hostable for production

### Configuration

#### Public API (Development/Staging)

```yaml
# application.yml - Development
delivery:
  routing:
    provider: osrm
    osrm:
      api-url: https://router.project-osrm.org
      timeout-seconds: 5
      cache-hours: 24
      fallback-to-haversine: true
      haversine-detour-factor: 1.2
```

**Rate Limits:**
- Public API: 20 requests/minute per IP
- Sufficient for Rentoza (typical booking creates 1 route call)
- Per-day estimate: 1,000 users × 1 route = 1,000 calls → 50 minutes of quota

#### Self-Hosted (Production)

For production deployment, consider self-hosting OSRM for unlimited capacity:

```bash
# Docker deployment
docker run -t -i -p 5000:5000 -v $(pwd):/data osrm/osrm-backend:v5.27.1 \
  osrm-extract /data/serbia-latest.osm.pbf && \
  osrm-partition /data/serbia-latest.osm.pbf && \
  osrm-customize /data/serbia-latest.osm.pbf && \
  osrm-routed --algorithm=mld /data/serbia-latest.osm.pbf
```

```yaml
# application.yml - Production (self-hosted)
delivery:
  routing:
    provider: osrm
    osrm:
      api-url: http://osrm-backend:5000  # Internal service
      timeout-seconds: 3                   # Faster local response
      cache-hours: 24
      fallback-to-haversine: true
```

### API Endpoint

```
GET /route/v1/driving/{lon1},{lat1};{lon2},{lat2}?overview=full

Example:
GET https://router.project-osrm.org/route/v1/driving/20.4612,44.8125;20.5271,44.7518?overview=full

Response:
{
  "code": "Ok",
  "routes": [{
    "distance": 8200,        // meters
    "duration": 600,         // seconds
    "geometry": "..._polyline_encoded_..."
  }],
  "waypoints": [...]
}
```

### Implementation

```java
@Service
@Slf4j
public class OsrmRoutingServiceClient implements RoutingServiceClient {
    
    private static final String OSRM_API = "${delivery.routing.osrm.api-url}";
    
    @Override
    public RoutingResponse getRoute(GeoPoint from, GeoPoint to, String profile) {
        String url = String.format("%s/route/v1/%s/%s,%s;%s,%s?overview=full",
            OSRM_API,
            profile,  // "driving", "walking", "cycling"
            from.getLongitude(), from.getLatitude(),
            to.getLongitude(), to.getLatitude()
        );
        
        // Query OSRM
        OsrmResponse response = restTemplate.getForObject(url, OsrmResponse.class);
        
        // Return fastest route (index 0)
        OsrmRoute route = response.getRoutes().get(0);
        
        return new RoutingResponse(
            route.getDistance(),      // meters
            route.getDuration(),      // seconds
            decodePolyline(route.getGeometry())
        );
    }
}
```

### Caching Strategy

Routes are cached for 24 hours because:
- ✅ Road network doesn't change daily
- ✅ Routes from A→B are always the same
- ✅ Saves API quota and improves response time

```java
@Configuration
public class RoutingCacheConfig {
    
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(10000)  // Cache up to 10k routes
        );
        return cacheManager;
    }
}

@Service
@Cacheable(value = "routes", key = "#from.latitude + '_' + #from.longitude + '_' + #to.latitude + '_' + #to.longitude")
public RoutingResponse getRoute(GeoPoint from, GeoPoint to) {
    // Actual OSRM call only happens on cache miss
    return osrmClient.getRoute(from, to, "driving");
}
```

### Graceful Degradation

If OSRM is unavailable, fall back to Haversine (air distance) with +20% detour factor:

```java
private RoutingResponse fallbackToHaversine(GeoPoint from, GeoPoint to) {
    double airDistance = from.distanceTo(to);  // Haversine
    double roadEstimate = airDistance * 1.2;   // +20% detour factor
    
    log.warn("OSRM unavailable, using Haversine fallback: {} km", roadEstimate/1000);
    
    return new RoutingResponse(roadEstimate, 0, null);
}
```

---

## 2. Mapbox GL JS

### Overview

Mapbox provides a modern web mapping library with excellent performance, offline support, and customization.

**Why Mapbox instead of Google Maps:**
- ✅ **Cheaper** - $0.50/1000 sessions vs $7/1000 loads (10x cheaper)
- ✅ **Faster** - Vector tiles load in ~800ms (rural Serbia)
- ✅ **Offline** - Pre-download tiles for connectivity dropouts
- ✅ **Better Customization** - Mapbox Studio for white-label styling
- ✅ **Vector Tiles** - Crisp on retina displays, responsive design

### Token Management

The Mapbox token is a **public token** (safe for browser use):

```
<MAPBOX_PUBLIC_TOKEN>
```

**Security Notes:**
- ✅ This token is safe in the browser (public access only)
- ✅ It has **NO** secret key (only public key)
- ❌ NEVER commit to Git - use environment variables
- ⚠️ Rotate token quarterly
- ✅ Set domain restrictions in Mapbox dashboard (rentoza.rs only)

### Configuration (Angular)

#### Environment Setup

```typescript
// src/environments/environment.development.ts
export const environment = {
  production: false,
  mapbox: {
    accessToken: '<MAPBOX_PUBLIC_TOKEN>'
  }
};

// src/environments/environment.ts (Production)
export const environment = {
  production: true,
  mapbox: {
    accessToken: process.env['MAPBOX_TOKEN'] || ''
  }
};
```

#### .gitignore Protection

```bash
# .gitignore
# Environment files
.env
.env.local
.env.*.local

# Never commit environment variables
src/environments/environment.prod.ts
```

#### .env.local Pattern

```bash
# .env.local (NEVER commit to git)
MAPBOX_TOKEN=<MAPBOX_PUBLIC_TOKEN>
```

#### Build Process

```bash
# Load environment variables at build time
ng build --configuration production \
  --define=process.env.MAPBOX_TOKEN=$(cat .env.local | grep MAPBOX_TOKEN | cut -d'=' -f2)
```

### Docker Deployment

```dockerfile
# Dockerfile
FROM node:18-alpine as builder
WORKDIR /app
COPY . .
ARG MAPBOX_TOKEN
RUN npm ci && \
    npm run build -- --configuration production \
    --define=process.env.MAPBOX_TOKEN=$MAPBOX_TOKEN

FROM node:18-alpine
WORKDIR /app
COPY --from=builder /app/dist .
EXPOSE 4200
CMD ["npm", "start"]
```

```bash
# Build
docker build --build-arg MAPBOX_TOKEN=<MAPBOX_PUBLIC_TOKEN> -t rentoza-frontend .

# Or deploy with GitHub Actions
# .github/workflows/deploy.yml
- name: Build Docker Image
  run: |
    docker build \
      --build-arg MAPBOX_TOKEN=${{ secrets.MAPBOX_TOKEN }} \
      -t rentoza-frontend .
```

### Angular Integration

```typescript
// src/app/core/services/map.service.ts
import mapboxgl from 'mapbox-gl';
import { environment } from '../../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class MapService {
  
  constructor() {
    if (!environment.mapbox.accessToken) {
      throw new Error('MAPBOX_TOKEN not configured');
    }
    mapboxgl.accessToken = environment.mapbox.accessToken;
  }
  
  initializeMap(containerId: string, center: [number, number], zoom: number): mapboxgl.Map {
    return new mapboxgl.Map({
      container: containerId,
      style: 'mapbox://styles/mapbox/streets-v11',
      center,
      zoom,
      antialias: true,
      bearing: 0,
      pitch: 0
    });
  }
}
```

### Component Usage

```typescript
@Component({
  selector: 'app-location-picker',
  templateUrl: './location-picker.component.html'
})
export class LocationPickerComponent implements OnInit {
  
  private map: mapboxgl.Map;
  
  constructor(private mapService: MapService) {}
  
  ngOnInit() {
    this.map = this.mapService.initializeMap('map-container', [20.4612, 44.8125], 13);
    
    // Add marker
    const marker = new mapboxgl.Marker()
      .setLngLat([20.4612, 44.8125])
      .addTo(this.map);
    
    // Click to move marker
    this.map.on('click', (e) => {
      marker.setLngLat([e.lngLat.lng, e.lngLat.lat]);
      this.locationSelected.emit({
        latitude: e.lngLat.lat,
        longitude: e.lngLat.lng
      });
    });
  }
}
```

### Offline Support

Pre-download tiles for offline use:

```typescript
// Use Mapbox offline plugin (optional)
import MapboxGLOffline from '@mapbox/mapbox-gl-offline';

// Download tiles for Serbia area
const tiles = new MapboxGLOffline('map-container');
tiles.downloadTiles([
  { min_zoom: 0, max_zoom: 14 },
  { north: 47.9, south: 42.2, east: 23.0, west: 18.8 }
]);
```

### Mapbox Studio (Custom Styling)

Create custom map style in Mapbox Studio:
1. Go to https://studio.mapbox.com/
2. Create new style based on "Streets"
3. Customize colors, fonts, labels for Rentoza branding
4. Copy style URL: `mapbox://styles/user/style-id`

```typescript
this.map = new mapboxgl.Map({
  style: 'mapbox://styles/kljaja01/custom-rentoza-map'  // Custom style
});
```

### Cost Estimation

```
Monthly Breakeven:
- Google Maps: $7/1000 loads → 10,000 loads = $70
- Mapbox: $0.50/1000 sessions → 10,000 sessions = $5

Rentoza Projection (100k users/month):
- Google Maps: 100,000 loads × $7/1000 = $700/month
- Mapbox: 100,000 sessions × $0.50/1000 = $50/month
- Savings: $650/month

Annual Savings: $7,800
```

---

## 3. Integration Checklist

### Backend (Spring Boot)

- [ ] Add RestTemplate bean with OSRM timeout configuration
- [ ] Implement OsrmRoutingServiceClient
- [ ] Add Route caching (Caffeine, 24-hour TTL)
- [ ] Add Haversine fallback logic
- [ ] Configuration properties (api-url, timeout, cache)
- [ ] Tests: OSRM happy path, OSRM unavailable, cache hits
- [ ] Metrics: OSRM latency, fallback count

### Frontend (Angular)

- [ ] Install mapbox-gl: `npm install mapbox-gl`
- [ ] Add Mapbox token to environment
- [ ] Add .env.local to .gitignore
- [ ] Create MapService for initialization
- [ ] Update LocationPicker component
- [ ] Update FuzzyCircle component
- [ ] Update PinDrop component
- [ ] Tests: map initialization, marker interactions, offline mode

### DevOps/Deployment

- [ ] Add MAPBOX_TOKEN to GitHub Secrets
- [ ] Update Dockerfile to build with token
- [ ] Update docker-compose.yml for local development
- [ ] Update production deployment config
- [ ] Set Mapbox token restrictions (domain whitelist)
- [ ] Configure OSRM self-hosting (optional, for production)

### Documentation

- [ ] Add OSRM & Mapbox to README
- [ ] Document token rotation process (quarterly)
- [ ] Document offline tile downloads
- [ ] Document OSRM self-hosting setup

---

## 4. Troubleshooting

### OSRM Issues

**Problem:** OSRM returns "400 Bad Request"
- Check coordinate order: OSRM expects `lon,lat` (not `lat,lon`)
- Check coordinates are within Serbia bounds

**Problem:** OSRM times out (>5 sec)
- Use self-hosted OSRM (internal network, no latency)
- Check Serbia OSM data is up-to-date
- Increase timeout to 10 seconds if roads are complex

**Problem:** Caching causes stale routes
- Routes are cached for 24 hours - acceptable for Rentoza
- If needed, reduce to 1 hour (trade-off: more API calls)

### Mapbox Issues

**Problem:** "Mapbox token not configured"
- Check `.env.local` exists and has MAPBOX_TOKEN
- Check environment.ts imports the token
- Restart dev server: `ng serve`

**Problem:** Map doesn't load (blank gray area)
- Check browser console for errors
- Verify Mapbox token is valid (try in Mapbox Studio)
- Check network throttling isn't too aggressive
- Verify Mapbox domain is in DNS (test: `nslookup api.mapbox.com`)

**Problem:** Token exposed in production
- Check .gitignore has `.env*` patterns
- Check GitHub Secrets are used in CI/CD
- Rotate token immediately (Mapbox dashboard)
- Audit token usage logs

---

## Summary

| Service | Purpose | Cost | Fallback |
|---------|---------|------|----------|
| **OSRM** | Delivery distance routing | Free | Haversine + 20% |
| **Mapbox GL JS** | Frontend maps | $0.50/1k sessions | Static image |

**Timeline:**
- Week 1-2: Backend integration (OSRM)
- Week 2-3: Frontend integration (Mapbox)
- Week 3: Testing (unit, integration, E2E)
- Week 4+: Production deployment with token management
