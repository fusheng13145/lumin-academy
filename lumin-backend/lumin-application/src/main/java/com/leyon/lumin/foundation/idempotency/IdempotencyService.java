package com.leyon.lumin.foundation.idempotency;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.repository.IdempotencyRecordMapper;
import com.leyon.lumin.foundation.repository.IdempotencyRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 幂等记录适配服务（04 §5.1：idempotency_record 由 foundation 适配器所有）。
 * 作用域 = subjectId + method + path + key；同键不同请求摘要 → 409/B2003；
 * 保留 24h，过期行删除后允许新建；执行中并发由复合主键兜底（骨架简化：
 * 冲突即 B2003，不存伪成功结果）。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordMapper mapper;

    public IdempotencyService(IdempotencyRecordMapper mapper) {
        this.mapper = mapper;
    }

    /** 幂等作用域（调用方经 IdempotencyRules 构造摘要与规范化路径）。 */
    public record Scope(String subjectId, String method, String path, String key,
                        String requestDigest, LocalDateTime now) {
    }

    /** 命中未过期同键记录：摘要一致 → 返回待回放；摘要不同 → B2003。过期行就地删除。 */
    @Transactional
    public Optional<IdempotencyRow> findReplay(Scope scope) {
        IdempotencyRow row = mapper.findByKey(scope.subjectId(), scope.method(),
                scope.path(), scope.key());
        if (row == null) {
            return Optional.empty();
        }
        if (IdempotencyRules.isExpired(row.expiresAt(), scope.now())) {
            mapper.deleteByKey(scope.subjectId(), scope.method(), scope.path(), scope.key());
            return Optional.empty();
        }
        if (!row.requestDigest().equals(scope.requestDigest())) {
            throw new LuminBusinessException(ErrorCode.B2003);
        }
        return Optional.of(row);
    }

    /** 记录本次响应（响应体为完整信封 JSON）；并发冲突按 B2003 处理（骨架简化）。 */
    @Transactional
    public void record(Scope scope, int responseCode, String responseBodyJson) {
        try {
            mapper.insert(new IdempotencyRow(scope.subjectId(), scope.method(),
                    scope.path(), scope.key(), scope.requestDigest(), responseCode,
                    responseBodyJson, IdempotencyRules.expiresAt(scope.now())));
        } catch (DuplicateKeyException e) {
            throw new LuminBusinessException(ErrorCode.B2003);
        }
    }
}
