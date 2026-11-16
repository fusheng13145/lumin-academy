package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.WorkMapper;
import com.leyon.lumin.foundation.port.CaseOwnershipPort;
import org.springframework.stereotype.Service;

/** 案件归属端口适配器（catalog 域所有权：work 表唯一写入者/读取权威）。 */
@Service
public class CatalogOwnershipAdapter implements CaseOwnershipPort {

    private final WorkMapper workMapper;

    public CatalogOwnershipAdapter(WorkMapper workMapper) {
        this.workMapper = workMapper;
    }

    @Override
    public String authorIdOfWork(String workId) {
        var row = workId == null ? null : workMapper.findById(workId);
        return row == null ? null : row.authorId();
    }
}
