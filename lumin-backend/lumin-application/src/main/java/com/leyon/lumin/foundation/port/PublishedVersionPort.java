package com.leyon.lumin.foundation.port;

/**
 * 当前公开版本查询端口（02 §3.1：foundation 声明，publication 实现）。
 * 举报建案时按目标冻结当时的 publication_pointer 版本，供后续受控阅卷读取
 * 不可变材料；举报方不得跨模块访问 publication Repository。
 */
public interface PublishedVersionPort {

    /** 目标当前公开版本；未发布或参数为空返回 null。targetType 为 WORK/CHAPTER。 */
    String findPublishedVersionId(String targetType, String targetId);
}
