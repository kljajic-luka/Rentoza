/**
 * Custom esbuild configuration to remove console logs in production builds.
 * 
 * This plugin runs during the Angular build process and automatically
 * removes console.log, console.debug, and console.info statements from
 * production bundles while keeping them in development.
 * 
 * Usage: Automatically applied via angular.json when building for production.
 */

const removeConsoleLogs = {
  name: 'remove-console-logs',
  setup(build) {
    // Only apply in production builds
    if (build.initialOptions.define?.['ngDevMode'] === 'false') {
      build.initialOptions.drop = build.initialOptions.drop || [];
      if (!build.initialOptions.drop.includes('console')) {
        // This tells esbuild to remove console.* calls
        build.initialOptions.pure = build.initialOptions.pure || [];
        build.initialOptions.pure.push('console.log');
        build.initialOptions.pure.push('console.debug');
        build.initialOptions.pure.push('console.info');
        build.initialOptions.pure.push('console.trace');
      }
    }
  }
};

export default removeConsoleLogs;
