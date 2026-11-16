package com.leyon.lumin.foundation.port;

/**
 * 案件归属查询端口（02 §3.1：foundation 声明端口，catalog 提供适配器）。
 * 申诉「本人案件」校验经此取作品作者权威记录（INV-07），moderation 不跨模块
 * 访问 catalog Repository。
 */
public interface CaseOwnershipPort {

    /** 作品作者（user_profile.id）；作品不存在或已软删返回 null。 */
    String authorIdOfWork(String workId);
}
