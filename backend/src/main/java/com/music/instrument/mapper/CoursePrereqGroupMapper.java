package com.music.instrument.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.music.instrument.domain.CoursePrereqGroup;

/**
 * 课程先修组 Mapper。
 * 路径解析要求关系全量批量读取：findAll 一次取走全部组，查询次数不随课程数量增长。
 */
@Mapper
public interface CoursePrereqGroupMapper {

    /** 全量读取先修组（课程数量有限，一次 IN/全表读走，禁止按课程逐条查）。 */
    List<CoursePrereqGroup> findAll();
}
