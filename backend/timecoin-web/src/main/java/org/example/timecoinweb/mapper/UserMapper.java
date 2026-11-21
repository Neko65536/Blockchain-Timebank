package org.example.timecoinweb.mapper;

import org.apache.ibatis.annotations.*;
import org.example.pojo.Activity;
import org.example.pojo.User;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

@Mapper
public interface UserMapper {

    /**
     * 根据id删除用户
     * @param ids
     */
    void deleteById(@Param("ids") List<Integer> ids);

    /**
     * 更新用户数据
     * @param user
     */
    void update(User user) throws DuplicateKeyException;

    /**
     *新增用户信息
     * @param user
     */
    @Insert("insert into `user`(username, password, role, email, age, phone, address, name, create_time, update_time) " +
            "VALUES(#{username},#{password},#{role},#{email},#{age},#{phone},#{address},#{name},#{createTime},#{updateTime}) ")
    void insert(User user);

    /**
     * 管理员查询用户
     * @param role
     * @param address
     * @param username
     * @param minAge
     * @param maxAge
     * @return
     */
    List<User> selectUsers(@Param("role") Short role,@Param("address") String address,@Param("username") String username,@Param("minAge") Short minAge,@Param("maxAge") Short maxAge);

    //更新图片本地地址
    @Update("update `user` set image=#{local},update_time=now() where id=#{id}")
    void updateImage(@Param("local") String local,@Param("id") Integer id);

    /**
     * 统计各角色人数
     * @return 包含 role 和 count 的 map 列表
     */
    /** 明确别名，避免 JDBC/MyBatis 在部分环境下将列名映射为大写或 count 保留字导致取不到键 */
    @Select("SELECT role AS user_role, COUNT(*) AS role_cnt FROM `user` GROUP BY role")
    List<java.util.Map<String, Object>> countUsersByRole();

    @Select("select id, username, name, role, coin_balance as coinBalance, balance_sync_time as balanceSyncTime, status, "
            + "token_version as tokenVersion, failed_attempts as failedAttempts, locked_until as lockedUntil, "
            + "must_change_password as mustChangePassword from `user` where id = #{userId}")
    User selectById(@Param("userId") Integer userId);

    /** N1：递增令牌版本，使该用户此前签发的所有 access/refresh token 立即失效（登出/改密调用）。 */
    @Update("update `user` set token_version = token_version + 1, update_time = now() where id=#{userId}")
    int bumpTokenVersion(@Param("userId") Integer userId);

    /** N2：登录失败计数与锁定（生产可配；dev 默认不启用）。 */
    @Update("update `user` set failed_attempts=#{n}, locked_until=#{lockedUntil}, update_time=now() where id=#{userId}")
    int updateLoginFailure(@Param("userId") Integer userId, @Param("n") int n, @Param("lockedUntil") java.time.LocalDateTime lockedUntil);

    @Update("update `user` set failed_attempts=0, locked_until=null, must_change_password=0, update_time=now() where id=#{userId}")
    int clearLoginFailureAndForceFlag(@Param("userId") Integer userId);

    /** J4：冻结/解冻账号；status 仅允许 1（正常）或 0（冻结） */
    @Update("update `user` set status=#{status}, update_time=now() where id=#{userId}")
    int setStatus(@Param("userId") Integer userId, @Param("status") Short status);

    @Select("select id, username, name, role, coin_balance as coinBalance, balance_sync_time as balanceSyncTime from `user`")
    List<User> selectAllForBalanceReconcile();

    @Update("update `user` set coin_balance = coin_balance + #{delta}, update_time = now() where id = #{id}")
    int addCoinBalance(@Param("id") Integer id, @Param("delta") long delta);

    @Update("update `user` set coin_balance = coin_balance - #{amount}, update_time = now() where id = #{id} and coin_balance >= #{amount}")
    int deductCoinBalance(@Param("id") Integer id, @Param("amount") long amount);

    @Update("update `user` set coin_balance = #{balance}, balance_sync_time = now(), update_time = now() where id = #{id}")
    void setCoinBalance(@Param("id") Integer id, @Param("balance") long balance);
}
