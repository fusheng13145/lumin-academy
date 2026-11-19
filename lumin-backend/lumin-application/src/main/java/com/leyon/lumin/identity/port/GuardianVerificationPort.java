package com.leyon.lumin.identity.port;

import java.time.LocalDateTime;

/**
 * 监护关系核验端口（05 §3.1 createConsent）：由受信核验服务回查监护人与 childId
 * 的真实关系及 scope，禁止仅凭客户端传入 childId 直接绑定。生产未接供应商时
 * 默认失败关闭（D2001）；测试用确定性替身实现。
 */
public interface GuardianVerificationPort {

    /** true=关系与 scope 核验通过；false=明确不通过（A2002）；不可用应抛 D2001。 */
    boolean verify(String guardianId, String childId, String scope, String proofRef,
                   LocalDateTime now);
}
