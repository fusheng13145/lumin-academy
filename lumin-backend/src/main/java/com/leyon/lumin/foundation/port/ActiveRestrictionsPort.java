package com.leyon.lumin.foundation.port;

import com.leyon.lumin.foundation.values.RestrictionKind;

import java.util.Set;

/**
 * 生效治理限制查询端口（02 §3.1：foundation 声明端口，governance 提供适配器）。
 * 读者三态裁决（VisibilityPolicy 步骤 2）经此端口读取生效限制，
 * governance 域保持对 reader 链的解耦（publication→foundation 允许边）。
 */
public interface ActiveRestrictionsPort {

    /**
     * 目标当前生效（ACTIVE）的限制种类集合；目标为章节时聚合父作品限制
     * （INV-03：父作品限制覆盖全部章节）。
     */
    Set<RestrictionKind> activeKinds(String targetType, String workId, String chapterId);
}
