package com.leyon.lumin.foundation.port;

/**
 * 档案年龄带读取端口（02 §3.2：engagement 不直接访问 identity.repository——
 * 跨模块 Repository 访问禁止；engagement → identity 经本端口）。
 * 实现方为 identity 域（UserProfileMapper 唯一写入者/读取者）。
 */
public interface ProfileBandPort {

    /**
     * 主体生效年龄带（user_profile.effective_age_band 原文）。
     * 档案缺失/已注销返回 null——调用方按未成年 fail-closed 口径处理。
     */
    String effectiveAgeBand(String subjectId);
}
