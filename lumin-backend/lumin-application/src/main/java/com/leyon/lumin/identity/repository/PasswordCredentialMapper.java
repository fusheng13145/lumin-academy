package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** password_credential 表数据访问（V20；口令摘要 + 库内锁定状态）。 */
@Mapper
public interface PasswordCredentialMapper {

    @Insert("""
            INSERT INTO password_credential (id, subject, login_id, email, password_hash,
                password_updated_at, failed_count, locked_until, last_login_at, state,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.subject}, #{row.loginId}, #{email},
                #{row.passwordHash}, #{row.passwordUpdatedAt}, #{row.failedCount},
                #{row.lockedUntil}, #{row.lastLoginAt}, #{row.state}, #{row.createdAt},
                #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") PasswordCredentialRow row, @Param("email") String email);

    /** 账号邮箱（V24；口令找回邮件通道的目标地址，未录入返回 null）。 */
    @Select("SELECT email FROM password_credential WHERE login_id = #{loginId}")
    String findEmailByLoginId(@Param("loginId") String loginId);

    @Select("""
            SELECT id, subject, login_id, password_hash, password_updated_at, failed_count,
                locked_until, last_login_at, state, created_at, updated_at, created_by,
                updated_by, lock_version
            FROM password_credential WHERE login_id = #{loginId}
            """)
    PasswordCredentialRow findByLoginId(@Param("loginId") String loginId);

    @Select("""
            SELECT id, subject, login_id, password_hash, password_updated_at, failed_count,
                locked_until, last_login_at, state, created_at, updated_at, created_by,
                updated_by, lock_version
            FROM password_credential WHERE subject = #{subject}
            """)
    PasswordCredentialRow findBySubject(@Param("subject") String subject);

    /** 记一次失败（locked_until 非空即落锁定；计数与锁定由服务层裁决）。 */
    @Update("""
            UPDATE password_credential
            SET failed_count = #{failedCount}, locked_until = #{lockedUntil},
                updated_at = #{now}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int updateFailureState(@Param("id") String id, @Param("failedCount") int failedCount,
                           @Param("lockedUntil") LocalDateTime lockedUntil,
                           @Param("now") LocalDateTime now);

    /** 登录成功：清零失败计数并记录最近登录时间。 */
    @Update("""
            UPDATE password_credential
            SET failed_count = 0, locked_until = NULL, last_login_at = #{now},
                updated_at = #{now}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int resetSuccess(@Param("id") String id, @Param("now") LocalDateTime now);

    /** 改密：换摘要并清零锁定状态。 */
    @Update("""
            UPDATE password_credential
            SET password_hash = #{passwordHash}, password_updated_at = #{now},
                failed_count = 0, locked_until = NULL, updated_at = #{now},
                lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int updatePassword(@Param("id") String id, @Param("passwordHash") String passwordHash,
                       @Param("now") LocalDateTime now);
}
