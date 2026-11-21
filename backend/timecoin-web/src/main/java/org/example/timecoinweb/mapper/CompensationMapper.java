package org.example.timecoinweb.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.pojo.CompensationRecord;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CompensationMapper {

    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("insert into compensation_record(activity_id, volunteer_table_id, volunteer_user_id, elder_user_id, amount, status, create_time, update_time) " +
            "values(#{activityId}, #{volunteerTableId}, #{volunteerUserId}, #{elderUserId}, #{amount}, #{status}, #{createTime}, #{updateTime})")
    void insert(CompensationRecord record);

    @Select("select id, activity_id as activityId, volunteer_table_id as volunteerTableId, volunteer_user_id as volunteerUserId, elder_user_id as elderUserId, amount, status, create_time as createTime, update_time as updateTime " +
            "from compensation_record where status = 0 order by create_time desc")
    List<CompensationRecord> selectPending();

    @Select("select id, activity_id as activityId, volunteer_table_id as volunteerTableId, volunteer_user_id as volunteerUserId, elder_user_id as elderUserId, amount, status, create_time as createTime, update_time as updateTime " +
            "from compensation_record order by create_time desc")
    List<CompensationRecord> selectAll();

    @Select("select id, activity_id as activityId, volunteer_table_id as volunteerTableId, volunteer_user_id as volunteerUserId, elder_user_id as elderUserId, amount, status, create_time as createTime, update_time as updateTime " +
            "from compensation_record where id=#{id} for update")
    CompensationRecord lockById(@Param("id") Integer id);

    @Update("update compensation_record set status=#{status}, update_time=#{updateTime} where id=#{id}")
    void updateStatus(@Param("id") Integer id, @Param("status") Short status, @Param("updateTime") LocalDateTime updateTime);
}
