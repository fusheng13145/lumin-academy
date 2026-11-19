package com.leyon.lumin.identity.infrastructure;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.identity.port.AgeVerificationPort;
import com.leyon.lumin.identity.port.GuardianVerificationPort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 未配置外部核验供应商时的生产默认：失败关闭（D2001），不伪造 VERIFIED，
 * 不以模拟核验替代真实供应商（docs/09 K-02/W-02）。测试通过 @Primary 替身覆盖。
 */
@Component
public class UnconfiguredIdentityVerificationAdapter
        implements AgeVerificationPort, GuardianVerificationPort {

    @Override
    public Result verify(String subject, String proofRef, LocalDateTime now) {
        throw new LuminBusinessException(ErrorCode.D2001);
    }

    @Override
    public boolean verify(String guardianId, String childId, String scope, String proofRef,
                          LocalDateTime now) {
        throw new LuminBusinessException(ErrorCode.D2001);
    }
}
