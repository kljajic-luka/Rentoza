export const environment = {
  production: false,
  // ═══════════════════════════════════════════════════════════════════════════
  // STAGING API ENDPOINTS
  // ═══════════════════════════════════════════════════════════════════════════
  // Remote staging deployment targets API/chat domains directly.
  // NOTE: Do not use same-origin `/api` for Firebase hosting deploys,
  // because Firebase rewrite rules return index.html for unknown paths.
  baseApiUrl: 'https://api.rentoza.rs/api',
  baseUrl: 'https://api.rentoza.rs',
  chatApiUrl: 'https://chat.rentoza.rs/api',
  chatWsUrl: 'https://chat.rentoza.rs/ws',
  // ═══════════════════════════════════════════════════════════════════════════
  // SUPABASE CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════
  supabaseUrl: 'https://your-project.supabase.co',
  supabaseAnonKey: 'SUPABASE_ANON_KEY_PLACEHOLDER',
  auth: {
    useCookies: true,
    cookieAuthCanaryPercentage: 0,
  },
  checkIn: {
    requireLocation: false,
  },
  // ═══════════════════════════════════════════════════════════════════════════
  // MAPBOX CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════
  mapbox: {
    accessToken: 'MAPBOX_PUBLIC_TOKEN_PLACEHOLDER',
    styles: {
      light: 'mapbox://styles/mapbox/streets-v12',
      dark: 'mapbox://styles/mapbox/dark-v11',
    },
    style: 'mapbox://styles/mapbox/streets-v12',
    defaultCenter: {
      latitude: 44.8176,
      longitude: 20.4569,
    },
    defaultZoom: 12,
  },
  // ═══════════════════════════════════════════════════════════════════════════
  // PAYMENT PROVIDER CONFIGURATION — STAGING
  // ═══════════════════════════════════════════════════════════════════════════
  // Staging uses MOCK provider: credentialless, no Monri SDK needed.
  // Switch to 'monri' + set authenticityToken when ready for integration testing.
  payment: {
    providerMode: 'mock' as const, // Staging: credentialless mock
    allowMockInThisEnv: true, // Staging: mock allowed
    modeLabel: 'MOCK' as const, // Runtime payment badge label
  },
  monri: {
    authenticityToken: '', // Not needed for mock mode
    sdkUrl: 'https://ipgtest.monri.com/dist/components.js',
  },
};
