package com.hhy.dreamingrecall.archive;

import java.util.Objects;

public record ContentReferenceEnvelope(ContentReference reference, byte[] fallbackPayload) {
    public ContentReferenceEnvelope {
        Objects.requireNonNull(reference, "reference");
        fallbackPayload = fallbackPayload.clone();
    }

    @Override
    public byte[] fallbackPayload() {
        return fallbackPayload.clone();
    }
}
