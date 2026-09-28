package com.music.instrument.service.path;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.music.instrument.domain.CoursePrerequisite;
import com.music.instrument.domain.LessonCourse;

/**
 * 一次路径解析所需的全部数据快照：课程、先修关系、学员已完成/在学课程。
 * 批量入口一次加载（课程 IN / 关系全量 / 学员记录 IN），解析只在内存建图，
 * 查询次数不随课程数量、依赖深度增加。
 *
 * @param courses       课程表（可能不含被关系引用的 id —— 那些按占位缺失课程处理）
 * @param prerequisites 全量先修关系
 * @param completed     学员已完成（仅 COMPLETED 状态）的课程 id
 * @param learning      学员有未取消、未完成学习记录的课程 id（LEARNING / 旧 NORMAL / NULL 状态）
 */
public record PathSnapshot(List<LessonCourse> courses,
                           List<CoursePrerequisite> prerequisites,
                           Set<Long> completed,
                           Set<Long> learning) {

    public Map<Long, LessonCourse> courseMap() {
        return courses.stream().collect(java.util.stream.Collectors.toMap(
                LessonCourse::getId, c -> c, (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
