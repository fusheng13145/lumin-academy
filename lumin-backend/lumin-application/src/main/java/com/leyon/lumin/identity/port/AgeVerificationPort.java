package com.leyon.lumin.identity.port;

import com.leyon.lumin.foundation.values.AgeBand;

import java.time.LocalDateTime;

/**
 * 年龄核验端口（05 §3.1 verifyAge）：由受信核验适配器回查 proofRef 与本人绑定，
 * 客户端只提交 proofRef，不得提交“已成年”结论。生产未接供应商时默认失败关闭
 * （D2001），测试用确定性替身实现；不得以模拟核验替代真实供应商（09 K-02/W-02）。
 */
public interface AgeVerificationPort {

    /** 核验结果；result 仅 VERIFIED/REJECTED，VERIFIED 时 ageBand 必须非空。 */
    record Result(String provider, String method, String result, AgeBand ageBand,
                  LocalDateTime verifiedAt, LocalDateTime expiresAt) {
    }

    Result verify(String subject, String proofRef, LocalDateTime now);
}
