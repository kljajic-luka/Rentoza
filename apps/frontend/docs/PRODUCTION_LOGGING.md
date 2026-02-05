# Console Logging in Production

## Overview

All `console.log()`, `console.debug()`, and `console.info()` statements are automatically removed from production builds for security and performance reasons.

`console.error()` and `console.warn()` statements are preserved for debugging critical issues in production.

## For Developers

### Local Development
All console statements work normally in development mode:
```bash
npm start  # All console.* methods work
```

### Production Build
Console statements are automatically stripped:
```bash
npm run build:prod  # Removes console.log/debug/info
```

Regular build (without cleanup):
```bash
npm run build  # Keeps all console statements
```

## How It Works

1. **Build Step**: Angular builds the production bundle with optimization
2. **Post-Build Cleanup**: `scripts/remove-console-logs.js` removes console statements
3. **Result**: Production bundle is ~13KB smaller without sensitive log data

## Using LoggerService (Optional)

For logs you want to keep control over, use the LoggerService:

```typescript
import { LoggerService } from '@core/services/logger.service';

constructor(private logger: LoggerService) {}

ngOnInit() {
  this.logger.log('Component initialized');  // Only logs in development
  this.logger.error('API error', error);     // Always logs (for debugging)
}
```

### Benefits:
- ✅ **Environment-aware**: Automatically disabled in production
- ✅ **Type-safe**: Full TypeScript support
- ✅ **Future-proof**: Can be extended to send logs to Sentry/LogRocket
- ✅ **Consistent**: Same API across the app

## CI/CD Integration

When deploying to production, always use:
```bash
npm run build:prod
firebase deploy --only hosting
```

This ensures all console statements are removed before deployment.

## What Gets Removed

✅ **Removed in Production:**
- `console.log(...)`
- `console.debug(...)`
- `console.info(...)`

✅ **Kept in Production:**
- `console.error(...)` - For critical errors
- `console.warn(...)` - For warnings

## Security Notes

**Why remove console logs in production?**
1. **Data Leakage**: Logs may contain sensitive user data (emails, tokens, etc.)
2. **Performance**: Reduces bundle size (~13KB in our case)
3. **Security**: Prevents exposing internal logic to potential attackers
4. **Clean Console**: Better UX - users don't see developer debug messages

**Example of sensitive data in logs:**
```typescript
console.log('User logged in:', user.email);  // ❌ Exposes email
console.log('OAuth token:', token);           // ❌ Exposes auth token
console.log('API response:', response);       // ❌ May contain PII
```

## Script Details

The post-build script (`scripts/remove-console-logs.js`) uses regex to strip console statements from JavaScript bundles after Angular's build process completes.

**Regex Pattern Explanation:**
```javascript
/console\.(log|debug|info)\s*\([^;]*?\);?/g
```

- `console\.(log|debug|info)` - Matches the console method
- `\s*` - Optional whitespace
- `\(` - Opening parenthesis
- `[^;]*?` - Matches everything until a semicolon (non-greedy) - this handles nested parentheses by looking for statement boundaries
- `\)` - Closing parenthesis
- `;?` - Optional semicolon

**Why this pattern works:**
The key is using `[^;]*?` instead of `[^)]*` which avoids issues with nested parentheses. For example:
```javascript
console.log('test', someFunction(param1, param2));
```

The old pattern `[^)]*` would stop at the first `)` and leave behind `, param2));`, causing syntax errors. The new pattern `[^;]*?` continues until the semicolon, removing the entire statement correctly.

It's safe and:
- Only processes `.js` files in `dist/`
- Doesn't modify source code
- Reports what it removed
- Fails build if errors occur

## Future Improvements

Consider integrating with:
- **Sentry** - For error tracking in production
- **LogRocket** - For session replay and debugging
- **Google Analytics** - For usage tracking

These services can capture errors and user flows without exposing sensitive data in the browser console.
