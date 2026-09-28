package com.music.instrument.dto.view;

import java.math.BigDecimal;

/**
 * 路径缺口/依赖链上的一门课（或占位）。
 * courseNo/title 为 null 表示关联课程已删除（missing=true），保留 id 占位并标数据缺口。
 */
public class GapCourse {

    private Long courseId;
    private String courseNo;
    private String title;
    private String teacherName;
    private String category;
    private BigDecimal price;
    private String status;
    private String statusName;

    /** 关联课程对象缺失（先修指向已删除课程）。 */
    private boolean missing;

    /** 该课已停开（历史完成仍可解锁，但不能作为新的学习目标）。 */
    private boolean closed;

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public String getCourseNo() {
        return courseNo;
    }

    public void setCourseNo(String courseNo) {
        this.courseNo = courseNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTeacherName() {
        return teacherName;
    }

    public void setTeacherName(String teacherName) {
        this.teacherName = teacherName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusName() {
        return statusName;
    }

    public void setStatusName(String statusName) {
        this.statusName = statusName;
    }

    public boolean isMissing() {
        return missing;
    }

    public void setMissing(boolean missing) {
        this.missing = missing;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }
}
