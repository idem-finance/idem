package finance.idem.application.security

/** Caller requested scopes that are not present on their own key — privilege escalation attempt. */
class InsufficientCallerScope(message: String) : Exception(message)
