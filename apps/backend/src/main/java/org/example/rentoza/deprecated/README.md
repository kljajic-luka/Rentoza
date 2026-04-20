# Deprecated Authentication Components

This package contains legacy authentication components from the custom JWT implementation (pre-Supabase).

## Status
**DEPRECATED** since v2.1.0. 
Marked for removal in v2.6.0 (approx. 6 months).

## Components
- **`jwt`**: Legacy JWT utilities, filters, and entry points.
- **`auth`**: Legacy Refresh Token service and related entities.

## why exist?
These components are kept as a **safe fallback** mechanism during the migration to Supabase Auth. 
If Supabase Auth fails or critical issues arise, we can rollback to this implementation quickly.

## Removal Plan
1. Monitor logs for usage (currently suppressed in `application.properties`).
2. If zero usage after 3 months, consider archival.
3. Remove code in v2.6.0.

## Do NOT use for new features.
Use `SupabaseAuthService` and `SupabaseJwtUtil` instead.

**Maintainer:** Rentoza Security Team
