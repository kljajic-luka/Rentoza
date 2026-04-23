export const environment = {
  production: false,
  baseApiUrl: '',
  baseUrl: '',
  chatApiUrl: '',
  chatWsUrl: '',
  supabaseUrl: '',
  supabaseAnonKey: '',
  auth: {
    useCookies: true,
    cookieAuthCanaryPercentage: 0,
  },
  checkIn: {
    requireLocation: true,
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
    providerMode: 'monri' as const,
    allowMockInThisEnv: false,
    modeLabel: 'MONRI' as const,
  },
  monri: {
    authenticityToken: '',
    sdkUrl: '',
  },
};
