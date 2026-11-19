package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.port.ProfileBandPort;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 档案年龄带端口适配器（identity 域所有权：user_profile 唯一读取面）。
 * engagement（订阅推送闸）经 foundation 端口读取，不触碰 Repository。
 */
@Service
public class ProfileBandAdapter implements ProfileBandPort {

    private final UserProfileMapper userProfileMapper;

    public ProfileBandAdapter(UserProfileMapper userProfileMapper) {
        this.userProfileMapper = userProfileMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public String effectiveAgeBand(String subjectId) {
        var row = userProfileMapper.findById(subjectId);
        return row == null ? null : row.effectiveAgeBand();
    }
}
