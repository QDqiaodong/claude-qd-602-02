package com.music.instrument.dto.view;

import java.util.List;

/**
 * 一个先修组的解析结论。
 */
public class PrereqGroupView {

    private Long groupId;
    private Integer groupNo;

    /** ALL 全部完成 / ANY 等价先修组（完成任意一门）。 */
    private String matchMode;
    private String matchModeName;
    private String remark;

    private List<PrereqItemView> items;

    /** 本组是否已满足：ALL=全部满足；ANY=任一满足（等价组不重复计数）。 */
    private boolean satisfied;

    /** 组内是否存在数据缺口路线（关联缺失 / 环路）。 */
    private boolean dataGap;

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Integer getGroupNo() {
        return groupNo;
    }

    public void setGroupNo(Integer groupNo) {
        this.groupNo = groupNo;
    }

    public String getMatchMode() {
        return matchMode;
    }

    public void setMatchMode(String matchMode) {
        this.matchMode = matchMode;
    }

    public String getMatchModeName() {
        return matchModeName;
    }

    public void setMatchModeName(String matchModeName) {
        this.matchModeName = matchModeName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public List<PrereqItemView> getItems() {
        return items;
    }

    public void setItems(List<PrereqItemView> items) {
        this.items = items;
    }

    public boolean isSatisfied() {
        return satisfied;
    }

    public void setSatisfied(boolean satisfied) {
        this.satisfied = satisfied;
    }

    public boolean isDataGap() {
        return dataGap;
    }

    public void setDataGap(boolean dataGap) {
        this.dataGap = dataGap;
    }
}
