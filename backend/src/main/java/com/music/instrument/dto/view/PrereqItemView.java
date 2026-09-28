package com.music.instrument.dto.view;

/**
 * 一个先修项的解析结论：对应课程、该学员对它的学习状态、是否已满足、是否落在数据缺口路线上。
 */
public class PrereqItemView {

    private GapCourse course;

    /** COMPLETED 已完成 / NORMAL 正在学习 / CANCELED 已取消 / LEGACY_MISSING 状态缺失 / NONE 未学习 */
    private String learningState;
    private String learningStateName;

    /** 该项是否已经满足（仅已完成满足）。 */
    private boolean satisfied;

    /** 该项走不通：环路 / 关联缺失 / 已停开未完成。 */
    private boolean blocked;

    /** 数据缺口（关联课程缺失，或其上游存在数据缺口）。 */
    private boolean dataGap;

    private String cyclePath;

    public GapCourse getCourse() {
        return course;
    }

    public void setCourse(GapCourse course) {
        this.course = course;
    }

    public String getLearningState() {
        return learningState;
    }

    public void setLearningState(String learningState) {
        this.learningState = learningState;
    }

    public String getLearningStateName() {
        return learningStateName;
    }

    public void setLearningStateName(String learningStateName) {
        this.learningStateName = learningStateName;
    }

    public boolean isSatisfied() {
        return satisfied;
    }

    public void setSatisfied(boolean satisfied) {
        this.satisfied = satisfied;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public boolean isDataGap() {
        return dataGap;
    }

    public void setDataGap(boolean dataGap) {
        this.dataGap = dataGap;
    }

    public String getCyclePath() {
        return cyclePath;
    }

    public void setCyclePath(String cyclePath) {
        this.cyclePath = cyclePath;
    }
}
