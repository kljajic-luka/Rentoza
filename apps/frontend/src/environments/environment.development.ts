export const environment = {
  production: false,
  baseApiUrl: 'http://localhost:8080/api',
  baseUrl: 'http://localhost:8080',
  chatApiUrl: 'http://localhost:8081/api',
  chatWsUrl: 'http://localhost:8081/ws',
  supabaseUrl: '',
  supabaseAnonKey: '',
  auth: {
    useCookies: true,
    cookieAuthCanaryPercentage: 0,
  },
  checkIn: {
    requireLocation: false,
  },
  mapbox: {
    accessToken: '',
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
  payment: {
    providerMode: 'mock' as const,
    allowMockInThisEnv: true,
    modeLabel: 'MOCK' as const,
  },
  monri: {
    authenticityToken: '',
    sdkUrl: '',
  },
};
