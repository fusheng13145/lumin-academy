package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.port.ActiveRestrictionsPort;
import com.leyon.lumin.governance.repository.RestrictionMapper;
import org.springframework.stereotype.Service;

import com.leyon.lumin.foundation.values.RestrictionKind;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

/** 生效限制查询适配器（governance 域所有权：restriction 唯一写入者/读取权威）。 */
@Service
public class ActiveRestrictionsAdapter implements ActiveRestrictionsPort {

    private final RestrictionMapper restrictionMapper;

    public ActiveRestrictionsAdapter(RestrictionMapper restrictionMapper) {
        this.restrictionMapper = restrictionMapper;
    }

    /** ACTIVE 限制种类集合（父作品限制覆盖全部章节，INV-03）。 */
    @Override
    public Set<RestrictionKind> activeKinds(String targetType, String workId, String chapterId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Set<RestrictionKind> kinds = new HashSet<>();
        for (String kind : "CHAPTER".equals(targetType)
                ? restrictionMapper.selectActiveKinds(workId, chapterId, now)
                : restrictionMapper.selectActiveKinds(workId, null, now)) {
            kinds.add(RestrictionKind.valueOf(kind));
        }
        return kinds;
    }
}
