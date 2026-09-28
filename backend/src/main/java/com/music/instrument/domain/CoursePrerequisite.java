package com.music.instrument.domain;

import java.time.LocalDateTime;

/**
 * 课程先修项实体：某个先修组里要求的一门课。
 * prerequisiteId 可能指向已删除的课程（历史脏数据），路径解析时保留占位并标记数据缺口。
 */
public class CoursePrerequisite {

    private Long id;
    private Long groupId;
    private Long prerequisiteId;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Long getPrerequisiteId() {
        return prerequisiteId;
    }

    public void setPrerequisiteId(Long prerequisiteId) {
        this.prerequisiteId = prerequisiteId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
