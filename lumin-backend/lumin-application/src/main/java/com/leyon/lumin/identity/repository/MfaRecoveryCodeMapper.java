package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** mfa_recovery_code 表数据访问（V21；只存 SHA-256 摘要，单次使用）。 */
@Mapper
public interface MfaRecoveryCodeMapper {

    @Insert("""
            INSERT INTO mfa_recovery_code (id, subject, code_hash, used_at, created_at)
            VALUES (#{id}, #{subject}, #{codeHash}, NULL, #{now})
            """)
    int insert(@Param("id") String id, @Param("subject") String subject,
               @Param("codeHash") String codeHash, @Param("now") LocalDateTime now);

    @Select("SELECT id FROM mfa_recovery_code WHERE subject = #{subject} "
            + "AND code_hash = #{codeHash} AND used_at IS NULL")
    String findUnusedId(@Param("subject") String subject,
                        @Param("codeHash") String codeHash);

    @Update("""
            UPDATE mfa_recovery_code SET used_at = #{now}
            WHERE id = #{id} AND used_at IS NULL
            """)
    int markUsed(@Param("id") String id, @Param("now") LocalDateTime now);

    /** 解绑时作废该主体全部恢复码（含已用，统一清空，避免"旧码换新设备"）。 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM mfa_recovery_code WHERE subject = #{subject}")
    int deleteAllFor(@Param("subject") String subject);
}
