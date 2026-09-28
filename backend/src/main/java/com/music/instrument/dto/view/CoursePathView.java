package com.music.instrument.dto.view;

import java.util.List;

/**
 * 学员课程路径结论（列表 / 详情 / 单查 / 批量查共用同一解析结果，结论必然一致）。
 *
 * verdict：AVAILABLE 可学 / BLOCKED 受阻 / CYCLE 先修关系存在环路（数据问题）。
 * 已停开但没有未满足先修的课：verdict 仍为 AVAILABLE，closed=true 由前端提示不能新约
 * （约课入口另有停开硬闸，路径视图负责解释「为什么」）。
 */
public class CoursePathView {

    private Long courseId;
    private String courseNo;
    private String title;

    /** COMPLETED / NORMAL / CANCELED / LEGACY_MISSING / NONE。 */
    private String learningState;
    private String learningStateName;

    /** AVAILABLE 可学 / BLOCKED 受阻 / CYCLE 循环。 */
    private String verdict;
    private String verdictName;
    private String summary;

    private boolean closed;
    private boolean dataGap;

    /** 直接先修分组（ALL/ANY 原样保留）。 */
    private List<PrereqGroupView> groups;

    /** 最短缺口路径：从最底层先修到直接先修有序、去重后的仍缺课程（共享节点不重复计数）。 */
    private List<GapCourse> gapPath;
    private int missingCount;
    private int missingLevel;

    /** 命中环路时给出的可读循环路径，如 C-6124 → C-6125 → C-6124。 */
    private String cyclePath;

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

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getVerdictName() {
        return verdictName;
    }

    public void setVerdictName(String verdictName) {
        this.verdictName = verdictName;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public boolean isDataGap() {
        return dataGap;
    }

    public void setDataGap(boolean dataGap) {
        this.dataGap = dataGap;
    }

    public List<PrereqGroupView> getGroups() {
        return groups;
    }

    public void setGroups(List<PrereqGroupView> groups) {
        this.groups = groups;
    }

    public List<GapCourse> getGapPath() {
        return gapPath;
    }

    public void setGapPath(List<GapCourse> gapPath) {
        this.gapPath = gapPath;
    }

    public int getMissingCount() {
        return missingCount;
    }

    public void setMissingCount(int missingCount) {
        this.missingCount = missingCount;
    }

    public int getMissingLevel() {
        return missingLevel;
    }

    public void setMissingLevel(int missingLevel) {
        this.missingLevel = missingLevel;
    }

    public String getCyclePath() {
        return cyclePath;
    }

    public void setCyclePath(String cyclePath) {
        this.cyclePath = cyclePath;
    }
}
