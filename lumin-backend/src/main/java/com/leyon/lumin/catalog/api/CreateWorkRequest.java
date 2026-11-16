package com.leyon.lumin.catalog.api;

/**
 * 作品创建载荷（05 §3.2 createWork：新作品身份 + DRAFT，不直接公开元数据；
 * 标题/简介等元数据全部经草稿-版本-审核链路，不走 published work 旁路）。
 */
public record CreateWorkRequest() {
}
