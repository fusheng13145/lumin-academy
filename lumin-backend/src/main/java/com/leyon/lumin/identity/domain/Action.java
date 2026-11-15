package com.leyon.lumin.identity.domain;

/** 授权动作白名单，与 01 §3.3 能力/禁止项一一对应（docs/10 A.3.3）。 */
public enum Action {
    READ_PUBLIC,
    READ_PRIVATE_DRAFT,
    WRITE_DRAFT,
    SUBMIT_DRAFT,
    DECIDE_CASE,
    CLAIM_CASE,
    APPROVE_ROLE_GRANT,
    REVIEW_CONFIG,
    REVIEW_RESTRICTION,
    REVIEW_APPEAL,
    MANAGE_ROLES,
    READ_AUDIT,
    DELETE_AUDIT,
    SYSTEM_PUBLISH
}
