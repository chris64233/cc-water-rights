package com.chris64233.cc.waterrights.error;

public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public static final String PERMIT_NOT_FOUND = "PERMIT_NOT_FOUND";
    public static final String PERMIT_NO_DUPLICATED = "PERMIT_NO_DUPLICATED";
    public static final String INVALID_DATE_RANGE = "INVALID_DATE_RANGE";

    public static final String EVENT_DATE_OUT_OF_RANGE = "EVENT_DATE_OUT_OF_RANGE";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String EVENT_NOT_FOUND = "EVENT_NOT_FOUND";
    public static final String IDEMPOTENCY_CONTENT_CONFLICT = "IDEMPOTENCY_CONTENT_CONFLICT";

    public static final String REVERSAL_NOT_REVERSIBLE = "REVERSAL_NOT_REVERSIBLE";
    public static final String REVERSAL_VOLUME_MISMATCH = "REVERSAL_VOLUME_MISMATCH";
    public static final String DECLARATION_ALREADY_REVERSED = "DECLARATION_ALREADY_REVERSED";

    private ErrorCodes() {
    }
}
