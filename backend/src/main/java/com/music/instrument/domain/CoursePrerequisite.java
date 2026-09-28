package com.music.instrument.domain;

import java.time.LocalDateTime;

/**
 * 课程先修关系：
 * requireType=ALL 表示一项必须完成的先修；
 * requireType=ELECTIVE 时同 groupNo 的多行组成一个等价先修组（组内任意完成一门即可）。
 * 不加数据库外键：prereqId 可能指向已删除课程，解析时以占位课程承接（数据缺口）。
 */
public class CoursePrerequisite {

    private Long id;
    private Long courseId;
    private Long prereqId;
    private String requireType;
    private Integer groupNo;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getPrereqId() {
        return prereqId;
    }

    public void setPrereqId(Long prereqId) {
        this.prereqId = prereqId;
    }

    public String getRequireType() {
        return requireType;
    }

    public void setRequireType(String requireType) {
        this.requireType = requireType;
    }

    public Integer getGroupNo() {
        return groupNo;
    }

    public void setGroupNo(Integer groupNo) {
        this.groupNo = groupNo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
