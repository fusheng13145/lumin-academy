package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.values.VisibilityReason;

/** 三态裁决输出（03 §3.1）；allowed=false 时 versionId 必须为 null。 */
public record VisibilityDecision(
        boolean allowed,
        VisibilityReason reason,
        String versionId,
        boolean raiseIncident) {
}
