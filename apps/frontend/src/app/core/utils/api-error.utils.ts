import { HttpErrorResponse } from '@angular/common/http';

type ApiErrorEnvelope = {
  error?:
    | {
        code?: string;
        message?: string;
        details?: Record<string, unknown>;
        path?: string;
        requestId?: string;
        retryAfter?: number;
      }
    | string;
  code?: string;
  message?: string;
  details?: Record<string, unknown>;
  userMessage?: string;
  errorCodes?: string[];
  earliestAllowedTime?: string;
  minutesUntilAllowed?: number;
  retryAfterSeconds?: number;
};

function getPayload(source: unknown): ApiErrorEnvelope {
  if (typeof source === 'string') {
    return { message: source };
  }
  if (!source || typeof source !== 'object') {
    return {};
  }
  return source as ApiErrorEnvelope;
}

export function getApiErrorCode(source: unknown): string | undefined {
  const payload = getPayload(source);
  if (typeof payload.error === 'string') {
    return payload.code;
  }
  return payload.error?.code ?? payload.code;
}

export function getApiErrorMessage(source: unknown): string | undefined {
  const payload = getPayload(source);
  if (typeof payload.error === 'string') {
    return payload.error;
  }
  return payload.error?.message ?? payload.message ?? payload.userMessage;
}

export function getApiErrorCodes(source: unknown): string[] | undefined {
  return getPayload(source).errorCodes;
}

export function getApiErrorValue<T = unknown>(source: unknown, key: string): T | undefined {
  const payload = getPayload(source) as Record<string, unknown>;
  return payload[key] as T | undefined;
}

export function getHttpErrorMessage(error: unknown): string | undefined {
  if (error instanceof HttpErrorResponse) {
    return getApiErrorMessage(error.error) ?? error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return getApiErrorMessage(error);
}