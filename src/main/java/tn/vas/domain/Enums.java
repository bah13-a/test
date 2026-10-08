package tn.vas.domain;

public final class Enums {
    private Enums() {}

    public enum ServiceStatus { DRAFT, ACTIVE, SUSPENDED, CLOSED }
    public enum ServiceType { VOTE, QUIZ, PREMIUM_CONTENT, SUBSCRIPTION, ALERT }
    public enum ConsentMode { SIMPLE_OPT_IN, DOUBLE_OPT_IN, API_ACTIVATION }
    public enum EventType { MO, MT, SUBSCRIPTION, RENEWAL, ADJUSTMENT }
    public enum DlrBillingRule { ON_SUBMITTED, ON_DELIVERED }
    public enum Priority { TRANSACTIONAL, CONFIRMATION, BULK }
    public enum MtStatus { PENDING, SUBMITTED, DELIVERED, EXPIRED, UNDELIVERABLE, REJECTED, UNKNOWN, FAILED }
    public enum BillingStatus { PENDING, ACCEPTED, CHARGED, REJECTED, REVERSED, DISPUTED }
    public enum SubStatus { PENDING_CONFIRMATION, ACTIVE, STOPPED }
    public enum MoOutcome { ROUTED, UNKNOWN_KEYWORD, DUPLICATE, SERVICE_CLOSED, LIMIT_REACHED, STOPPED, HELP, BLOCKED }
    public enum ReconResult { MATCHED, AMOUNT_MISMATCH, MISSING_ON_PLATFORM, MISSING_ON_OPERATOR, STATUS_MISMATCH }
}
