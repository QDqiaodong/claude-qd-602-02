package com.music.instrument.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.music.instrument.domain.Enrollment;
import com.music.instrument.dto.view.EnrollmentView;

/**
 * 约课记录 Mapper。
 */
@Mapper
public interface EnrollmentMapper {

    List<EnrollmentView> list(@Param("studentId") Long studentId,
                              @Param("courseId") Long courseId);

    List<Enrollment> listByCourseId(@Param("courseId") Long courseId);

    /**
     * 按学员取原始约课记录（不 JOIN、不改写 status）。
     * 路径解析专用：status=NULL 的历史记录必须原样保留为「状态缺失」，不能丢也不能当成已完成。
     */
    List<Enrollment> listPlainByStudentId(@Param("studentId") Long studentId);

    /** 全量原始约课记录：一次查看某学员全部路径时与关系表一起批量读，查询次数为常数。 */
    List<Enrollment> listAllPlain();

    int countByStudentAndCourse(@Param("studentId") Long studentId,
                                @Param("courseId") Long courseId);

    int insert(Enrollment enrollment);

    /** 批量插入：一键多科报名一次落库（<foreach> 拼 VALUES）。 */
    int insertBatch(@Param("list") List<Enrollment> enrollments);

    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
