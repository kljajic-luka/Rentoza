export const environment = {
  production: true,
  // ═══════════════════════════════════════════════════════════════════════════
  // PRODUCTION API ENDPOINTS
  // ═══════════════════════════════════════════════════════════════════════════
  baseApiUrl: 'https://api.rentoza.rs/api',
  baseUrl: 'https://api.rentoza.rs',
  chatApiUrl: 'https://chat.rentoza.rs/api',
  // SockJS uses HTTPS, not WSS - it handles the WebSocket upgrade internally
  chatWsUrl: 'https://chat.rentoza.rs/ws',
  // ═══════════════════════════════════════════════════════════════════════════
  // SUPABASE CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════
  supabaseUrl: 'https://your-project.supabase.co',
  supabaseAnonKey: 'SUPABASE_ANON_KEY_PLACEHOLDER',
  auth: {
    useCookies: true, // Cookie-based authentication for production
    cookieAuthCanaryPercentage: 0,
  },
  // ═══════════════════════════════════════════════════════════════════════════
  checkIn: {
    requireLocation: true, // Production requires HTTPS, so geolocation works
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
    providerMode: 'monri' as const,
    allowMockInThisEnv: false,
    modeLabel: 'MONRI' as const,
  },
  monri: {
    authenticityToken: '', // Set via CI/CD — never commit production token
    sdkUrl: 'https://ipg.monri.com/dist/components.js',
  },
};
