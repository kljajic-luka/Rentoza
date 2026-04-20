export const environment = {
  production: false,
  // ═══════════════════════════════════════════════════════════════════════════
  // PROXY MODE - Uses Angular dev server proxy for same-origin cookie handling
  // ═══════════════════════════════════════════════════════════════════════════
  // The Angular proxy (proxy.conf.json) forwards /api/* to the backend
  // This ensures cookies work properly (same-origin = no SameSite issues)
  // ═══════════════════════════════════════════════════════════════════════════
  baseApiUrl: '/api',
  baseUrl: '',
  chatApiUrl: 'http://192.168.1.151:8081/api',
  chatWsUrl: 'ws://192.168.1.151:8081/ws',
  // ═══════════════════════════════════════════════════════════════════════════
  // SUPABASE CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════
  supabaseUrl: 'https://your-project.supabase.co',
  supabaseAnonKey: 'SUPABASE_ANON_KEY_PLACEHOLDER',
  auth: {
    useCookies: true, // ✅ ENABLED for E2E testing (cookie-based authentication)
    cookieAuthCanaryPercentage: 0, // Percentage of users in canary rollout
  },
  // ═══════════════════════════════════════════════════════════════════════════
  // DEVELOPMENT BYPASSES
  // ═══════════════════════════════════════════════════════════════════════════
  checkIn: {
    requireLocation: false, // Skip geolocation for HTTP testing (Safari requires HTTPS)
  },
  // ═══════════════════════════════════════════════════════════════════════════
  // MAPBOX CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════
  mapbox: {
    accessToken: 'MAPBOX_PUBLIC_TOKEN_PLACEHOLDER',
    // Map styles by theme
    styles: {
      light: 'mapbox://styles/mapbox/streets-v12',
      dark: 'mapbox://styles/mapbox/dark-v11',
    },
    // Default style (will be overridden by theme)
    style: 'mapbox://styles/mapbox/streets-v12',
    // Serbia default center (Belgrade)
    defaultCenter: {
      latitude: 44.8176,
      longitude: 20.4569,
    },
    defaultZoom: 12,
  },
  // ═══════════════════════════════════════════════════════════════════════════
  // PAYMENT PROVIDER CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════
  payment: {
    providerMode: 'mock' as const, // 'mock' | 'monri'
    allowMockInThisEnv: true, // Dev/staging: mock allowed
    modeLabel: 'MOCK' as const, // Runtime payment badge label
  },
  monri: {
    authenticityToken: 'test-authenticity-token', // Monri test environment token
    sdkUrl: 'https://ipgtest.monri.com/dist/components.js',
  },
};
