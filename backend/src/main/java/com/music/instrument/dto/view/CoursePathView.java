package com.music.instrument.dto.view;

import java.util.List;

/**
 * 学员课程路径视图：课程列表、课程详情、批量/单个约课校验共用同一份解析结论。
 */
public class CoursePathView {

    /** 结论码：READY 可学 / LEARNING 正在学习 / BLOCKED 受阻 / CLOSED_TARGET 已停开 /
     *  CYCLE 循环依赖 / DATA_GAP 数据缺口。 */
    private String conclusion;
    private String conclusionName;

    private Long studentId;
    private String studentName;

    private Long courseId;
    private String courseNo;
    private String title;
    private String teacherName;
    private String courseStatus;
    private boolean open;
    /** 关联课程缺失时为占位节点（数据缺口）。 */
    private boolean placeholder;

    /** 学员在该课的学习状态：COMPLETED / LEARNING / NORMAL / CANCELED / UNKNOWN（历史缺失）/ NONE（无记录）。 */
    private String learningStatus;
    private String learningStatusName;

    /** 仍缺少的最短先修路径（缺口路径）：深→浅，不含目标自身。 */
    private List<GapNode> missingPath;
    private Integer missingCount;

    /** 可读循环路径（编号序列，首尾相同），无环为空。 */
    private List<String> cyclePath;

    /** 直接先修要求（前端渲染「全部完成 / 任一完成」）。 */
    private List<RequirementView> requirements;

    /** 该路线是否命中数据缺口（依赖课程 / 学习记录关联对象缺失）。 */
    private boolean dataGap;

    /** 缺口路径上的一个课程节点。 */
    public static class GapNode {
        private Long courseId;
        private String courseNo;
        private String title;
        /** 相对目标课程的层级：直接先修为 1。 */
        private Integer depth;
        private boolean placeholder;
        private boolean open;
        private String status;

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

        public Integer getDepth() {
            return depth;
        }

        public void setDepth(Integer depth) {
            this.depth = depth;
        }

        public boolean isPlaceholder() {
            return placeholder;
        }

        public void setPlaceholder(boolean placeholder) {
            this.placeholder = placeholder;
        }

        public boolean isOpen() {
            return open;
        }

        public void setOpen(boolean open) {
            this.open = open;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /** 一项先修要求。 */
    public static class RequirementView {
        /** ALL 必须完成 / ELECTIVE 组内任一完成。 */
        private String requireType;
        private Integer groupNo;
        private List<GapNode> courses;

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

        public List<GapNode> getCourses() {
            return courses;
        }

        public void setCourses(List<GapNode> courses) {
            this.courses = courses;
        }
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getConclusionName() {
        return conclusionName;
    }

    public void setConclusionName(String conclusionName) {
        this.conclusionName = conclusionName;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

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

    public String getCourseStatus() {
        return courseStatus;
    }

    public void setCourseStatus(String courseStatus) {
        this.courseStatus = courseStatus;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public boolean isPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(boolean placeholder) {
        this.placeholder = placeholder;
    }

    public String getLearningStatus() {
        return learningStatus;
    }

    public void setLearningStatus(String learningStatus) {
        this.learningStatus = learningStatus;
    }

    public String getLearningStatusName() {
        return learningStatusName;
    }

    public void setLearningStatusName(String learningStatusName) {
        this.learningStatusName = learningStatusName;
    }

    public List<GapNode> getMissingPath() {
        return missingPath;
    }

    public void setMissingPath(List<GapNode> missingPath) {
        this.missingPath = missingPath;
    }

    public Integer getMissingCount() {
        return missingCount;
    }

    public void setMissingCount(Integer missingCount) {
        this.missingCount = missingCount;
    }

    public List<String> getCyclePath() {
        return cyclePath;
    }

    public void setCyclePath(List<String> cyclePath) {
        this.cyclePath = cyclePath;
    }

    public List<RequirementView> getRequirements() {
        return requirements;
    }

    public void setRequirements(List<RequirementView> requirements) {
        this.requirements = requirements;
    }

    public boolean isDataGap() {
        return dataGap;
    }

    public void setDataGap(boolean dataGap) {
        this.dataGap = dataGap;
    }
}
