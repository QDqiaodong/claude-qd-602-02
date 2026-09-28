package com.music.instrument.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.music.instrument.domain.CoursePrerequisite;

/**
 * 课程先修关系 Mapper。路径解析必须批量读取全部关系后在内存里建图，
 * 不允许按课程 / 按依赖深度逐条查询。
 */
@Mapper
public interface CoursePrerequisiteMapper {

    /** 全量先修关系（数据量小，一次读全，整图解析）。 */
    List<CoursePrerequisite> findAll();
}
