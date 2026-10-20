package com.digihealth.common;

import jakarta.validation.constraints.Size;

/** Optional { reason } / { note } bodies used by approve and reject buttons. */
public final class ReasonBody {

    private ReasonBody() {
    }

    public record Reason(@Size(max = 1000, message = "Please keep the reason under 1,000 characters") String reason) {
    }

    public record Note(@Size(max = 1000, message = "Please keep the note under 1,000 characters") String note) {
    }
}
