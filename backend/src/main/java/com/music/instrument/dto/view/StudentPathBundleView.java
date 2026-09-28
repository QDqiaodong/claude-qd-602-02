package com.music.instrument.dto.view;

import java.util.List;

/**
 * 一次查看某学员全部课程路径的批量结果：
 * 路径为整图一次解析（关系 / 记录均批量读取），另附学习记录关联课程缺失的孤儿记录占位。
 */
public class StudentPathBundleView {

    private Long studentId;
    private String studentNo;
    private String studentName;
    private List<CoursePathView> paths;
    /** 学习记录指向已删除课程：保留占位信息并标记数据缺口。 */
    private List<CoursePathView.GapNode> orphanEnrollments;

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public String getStudentNo() {
        return studentNo;
    }

    public void setStudentNo(String studentNo) {
        this.studentNo = studentNo;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public List<CoursePathView> getPaths() {
        return paths;
    }

    public void setPaths(List<CoursePathView> paths) {
        this.paths = paths;
    }

    public List<CoursePathView.GapNode> getOrphanEnrollments() {
        return orphanEnrollments;
    }

    public void setOrphanEnrollments(List<CoursePathView.GapNode> orphanEnrollments) {
        this.orphanEnrollments = orphanEnrollments;
    }
}
