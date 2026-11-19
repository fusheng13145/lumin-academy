package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.port.ReviewCaseDecisionPort;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import org.springframework.stereotype.Service;

/** review_case 生效裁决查询（moderation 所有权；供 publication 撤回前提使用）。 */
@Service
public class ReviewCaseDecisionAdapter implements ReviewCaseDecisionPort {

    private final ReviewCaseMapper reviewCaseMapper;

    public ReviewCaseDecisionAdapter(ReviewCaseMapper reviewCaseMapper) {
        this.reviewCaseMapper = reviewCaseMapper;
    }

    @Override
    public boolean hasEffectiveDecision(String caseId) {
        ReviewCaseRow row = caseId == null ? null : reviewCaseMapper.findById(caseId);
        return row != null && "DECIDED".equals(row.state());
    }
}
