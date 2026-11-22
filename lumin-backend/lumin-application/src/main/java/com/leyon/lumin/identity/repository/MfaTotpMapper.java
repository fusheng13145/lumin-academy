package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** mfa_totp_credential 表数据访问（V21；TOTP 种子仅以密文落库）。 */
@Mapper
public interface MfaTotpMapper {

    @Insert("""
            INSERT INTO mfa_totp_credential (id, subject, secret_cipher, status, last_used_step,
                activated_at, created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.subject}, #{row.secretCipher}, #{row.status},
                #{row.lastUsedStep}, #{row.activatedAt}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") MfaTotpRow row);

    @Select("SELECT * FROM mfa_totp_credential WHERE subject = #{subject}")
    MfaTotpRow findBySubject(@Param("subject") String subject);

    /** 重新登记：覆盖未确认的种子（仍为 PENDING）。 */
    @Update("""
            UPDATE mfa_totp_credential
            SET secret_cipher = #{secretCipher}, last_used_step = NULL, updated_at = #{now},
                updated_by = #{subject}, lock_version = lock_version + 1
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int updatePendingSecret(@Param("id") String id, @Param("subject") String subject,
                            @Param("secretCipher") byte[] secretCipher,
                            @Param("now") LocalDateTime now);

    /**
     * 确认生效（PENDING → ACTIVE）。
     *
     * <p>刻意**不**记录登记用的时间步：登记页只能由已登录者（或受限会话）打开，登记码
     * 一旦换发会话即失去意义；若在此记账，用户确认后 30 秒内的首次登录会被重放保护
     * 误拒。登录路径的 {@link #markStepUsed} 才是重放防线。
     */
    @Update("""
            UPDATE mfa_totp_credential
            SET status = 'ACTIVE', activated_at = #{now}, last_used_step = NULL,
                updated_at = #{now}, updated_by = #{subject}, lock_version = lock_version + 1
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int activate(@Param("id") String id, @Param("subject") String subject,
                 @Param("now") LocalDateTime now);

    /** 记录最近一次通过校验的时间步（同一时间步不可重复使用）。 */
    @Update("""
            UPDATE mfa_totp_credential
            SET last_used_step = #{step}, updated_at = #{now}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int markStepUsed(@Param("id") String id, @Param("step") long step,
                     @Param("now") LocalDateTime now);

    /**
     * 运维解绑：ACTIVE → REVOKED（种子保留以留痕，但不再参与校验；重放账本清零）。
     *
     * <p>只由 {@code MfaUnbindService} 在工单获批后调用，返回 0 表示当时已不是 ACTIVE。
     */
    @Update("""
            UPDATE mfa_totp_credential
            SET status = 'REVOKED', last_used_step = NULL, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE subject = #{subject} AND status = 'ACTIVE'
            """)
    int revoke(@Param("subject") String subject, @Param("operator") String operator,
               @Param("now") LocalDateTime now);
}
