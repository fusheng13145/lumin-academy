package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.springframework.stereotype.Service;

/**
 * 主体档案服务（identity 域所有权：02 §3.1）。authoring 经本服务读取
 * 生效年龄层与账号状态（允许边 authoring→identity）。
 * **骨架登记**：完整年龄证据链（AgePolicy.assess + guardian_consent 表 V2 迁移 +
 * 核验供应商适配）接入后，此处升级为证据驱动的 AgeAssessment。
 */
@Service
public class ProfileService {

    private final UserProfileMapper userProfileMapper;

    public ProfileService(UserProfileMapper userProfileMapper) {
        this.userProfileMapper = userProfileMapper;
    }

    /** 跨模块可见的档案摘要（不泄漏 identity.repository 行类型）。 */
    public record ProfileSummary(String id, String effectiveAgeBand, String accountStatus) {
    }

    /** 已认证主体档案缺失按 404/B2004 处理（骨架口径；createProfile 落地后不可达）。 */
    public ProfileSummary requireProfile(String userId) {
        UserProfileRow row = userId == null ? null : userProfileMapper.findById(userId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return new ProfileSummary(row.id(), row.effectiveAgeBand(), row.accountStatus());
    }
}
