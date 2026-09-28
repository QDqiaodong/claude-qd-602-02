package com.music.instrument.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.music.instrument.domain.CoursePrerequisite;

/**
 * 课程先修项 Mapper。
 */
@Mapper
public interface CoursePrerequisiteMapper {

    /** 全量读取先修项，与 {@link CoursePrereqGroupMapper#findAll()} 共同一次构成完整关系图。 */
    List<CoursePrerequisite> findAll();
}
