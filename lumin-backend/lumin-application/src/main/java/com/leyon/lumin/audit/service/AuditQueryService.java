package com.leyon.lumin.audit.service;

import com.leyon.lumin.audit.repository.AuditRecordMapper;
import com.leyon.lumin.audit.repository.AuditRecordRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 审计读取应用服务（05 §3.4 listAuditRecords：AUDITOR 授权作用域只读；
 * append-only——无更新/删除端点，T35 口径）。脱敏字段原样返回
 * （写入侧已保证不含正文/令牌/证件）。
 */
@Service
public class AuditQueryService {

    private static final String ROLE_AUDITOR = "AUDITOR";

    private final AuditRecordMapper mapper;

    public AuditQueryService(AuditRecordMapper mapper) {
        this.mapper = mapper;
    }

    /** 审查读取（先过滤后 limit；仅 AUDITOR 作用域）。 */
    @Transactional(readOnly = true)
    public List<AuditRecordRow> search(String stubRole, String resourceType, String resourceId,
                                       String actorId, String action, int limit) {
        if (!ROLE_AUDITOR.equals(stubRole)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        return mapper.search(emptyToNull(resourceType), emptyToNull(resourceId),
                emptyToNull(actorId), emptyToNull(action), Math.min(Math.max(limit, 1), 200));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
