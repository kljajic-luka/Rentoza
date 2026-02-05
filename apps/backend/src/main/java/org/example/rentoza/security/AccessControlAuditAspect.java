package org.example.rentoza.security;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * AOP aspect for auditing access control violations across the application.
 * Logs all AccessDeniedException events with user context and method details.
 * 
 * <p>Why this matters:
 * - Security incident tracking (who attempted unauthorized access)
 * - Compliance logging (audit trails for GDPR, SOC 2, etc.)
 * - Attack detection (repeated access denials may indicate probing)
 * - Debugging authorization issues in production
 * 
 * <p>What gets logged:
 * - Authenticated user (email and ID)
 * - Method that was called (service/controller + method name)
 * - Method arguments (for context)
 * - Denial reason (exception message)
 * - Timestamp (via Logback)
 * 
 * <p>Log format example:
 * WARN: Access denied — user=renter@example.com (ID: 5) method=BookingService.getBookingById(123) 
 * reason="Unauthorized to access booking 123: user is not the renter or owner"
 * 
 * @see org.springframework.security.access.AccessDeniedException
 */
@Aspect
@Component
@Slf4j
public class AccessControlAuditAspect {

    /**
     * Intercepts all AccessDeniedException thrown from service layer methods.
     * Logs detailed information about the failed access attempt.
     * 
     * <p>Pointcut targets:
     * - All methods in org.example.rentoza.*.service.* packages
     * - All methods in controller packages (via **..controller..*)
     * 
     * @param joinPoint Method execution context
     * @param ex The AccessDeniedException that was thrown
     */
    @AfterThrowing(
            pointcut = "execution(* org.example.rentoza..service..*(..)) || " +
                       "execution(* org.example.rentoza..controller..*(..))",
            throwing = "ex"
    )
    public void logAccessDenied(JoinPoint joinPoint, AccessDeniedException ex) {
        // Extract user context from SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userEmail = "anonymous";
        Long userId = null;

        if (auth != null && auth.isAuthenticated()) {
            Object principal = auth.getPrincipal();
            if (principal instanceof JwtUserPrincipal jwtPrincipal) {
                userEmail = jwtPrincipal.email();
                userId = jwtPrincipal.id();
            } else if (principal instanceof String) {
                userEmail = (String) principal; // Fallback for anonymous users
            }
        }

        // Extract method context
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        // Format arguments (limit to avoid log spam)
        String argsString = formatArguments(args);

        // Log the access denial event
        log.warn("Access denied — user={} (ID: {}) method={}.{}({}) reason=\"{}\"",
                userEmail, userId, className, methodName, argsString, ex.getMessage());

        // Optional: Send to security monitoring system (Splunk, Datadog, etc.)
        // securityMonitor.recordAccessDenial(userEmail, userId, className, methodName, ex.getMessage());
    }

    /**
     * Formats method arguments for logging.
     * Limits output to prevent log spam from large objects.
     * 
     * @param args Method arguments
     * @return Formatted string representation of arguments
     */
    private String formatArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "no args";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(args.length, 5); i++) { // Limit to first 5 args
            if (i > 0) sb.append(", ");
            
            Object arg = args[i];
            if (arg == null) {
                sb.append("null");
            } else if (arg instanceof String || arg instanceof Number || arg instanceof Boolean) {
                sb.append(arg);
            } else {
                // For complex objects, just show class name
                sb.append(arg.getClass().getSimpleName());
            }
        }

        if (args.length > 5) {
            sb.append(", ... (").append(args.length - 5).append(" more)");
        }

        return sb.toString();
    }
}
