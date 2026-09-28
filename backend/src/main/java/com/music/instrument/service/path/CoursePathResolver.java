package com.music.instrument.service.path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.music.instrument.domain.CoursePrerequisite;
import com.music.instrument.domain.LessonCourse;

/**
 * 课程路径统一解析器（无 Spring 依赖，可直接单测）。
 *
 * 输入一份 {@link PathSnapshot} 与目标课程 id，向前解析直接与多级依赖，输出结论与缺口路径：
 *  - 结论由装配层按节点状态映射：可学 / 正在学习 / 受阻 / 已停开 / 循环依赖 / 数据缺口
 *  - 缺口路径 = 选定路线上仍未完成的先修课程（不含目标自身；等价组只取选中的一门，
 *    不重复计数），按「深→浅」排列，深度相同按课程编号升序
 *  - 循环路径 = 闭合环上的课程编号序列（首尾相同），自指为 C-xxxx → C-xxxx
 *
 * 判定规则：
 *  1. 只有 COMPLETED 记录解锁；LEARNING / NORMAL / CANCELED / NULL 一律不解锁，但仍可展示。
 *  2. ALL 行各自是一项要求；同 groupNo 的 ELECTIVE 行组成等价先修组，组内任意一门满足即可。
 *  3. 每条要求先排除「硬阻断」成员（循环 / 数据缺口 / 停开未完成），在可走成员间择优：
 *     缺失课程数少 → 缺口层级浅 → 课程编号小；没有可走成员时才回退到最可读的阻断原因。
 *     例：组 [6|3]，6 还缺 {7,6} 两门、3 只缺 {3} 一门 → 选 3。
 *  4. 已停开课程：过去已完成仍可先修；未完成时该路线不可行且不能作为新学习目标。
 *  5. 关系指向已删除课程：保留 id 占位，该路线标记数据缺口。
 *  6. 自指 / 环路脏数据：依遍历栈检测并立即终止该路线，返回可读循环路径，不无限递归。
 */
public final class CoursePathResolver {

    /** 不可行原因优先级（越大越优先展示）。 */
    static final int FAIL_SOFT = 1;
    static final int FAIL_CLOSED = 2;
    static final int FAIL_DATA_GAP = 3;
    static final int FAIL_CYCLE = 4;

    /** 一项先修要求：ALL 单门，ELECTIVE 组内任意一门。 */
    public static final class Requirement {
        private final boolean elective;
        private final Integer groupNo;
        private final List<Long> prereqIds = new ArrayList<>();

        Requirement(boolean elective, Integer groupNo) {
            this.elective = elective;
            this.groupNo = groupNo;
        }

        public boolean isElective() {
            return elective;
        }

        public Integer getGroupNo() {
            return groupNo;
        }

        public List<Long> getPrereqIds() {
            return prereqIds;
        }
    }

    /** 解析节点（记忆化缓存 + 对外数据载体）。 */
    public static final class Plan {
        private Long courseId;
        private boolean placeholder;
        private boolean exists = true;
        private boolean open = true;
        private boolean completed;
        private boolean learning;
        private String courseNo;
        private String title;

        /** 当前先修是否已全部满足（沿完成链成立）。 */
        private boolean satisfied;
        /** 选定路线是否无硬阻断（课程存在且开放、无环、无数据缺口）。 */
        private boolean viable = true;
        private boolean cyclic;
        private boolean dataGap;
        private boolean closedUnfinished;
        private int failRank;
        /** 选定路线缺口链最大层级：完成节点 0；未完成叶子 1；其余 1+max(子层级)。 */
        private int depth;
        /** 选定路线去重后的缺失课程 id（含未完成的自身）。 */
        private final Set<Long> gapIds = new LinkedHashSet<>();
        /** 缺口节点（合并用；最终顺序由装配层按 chosenPrereqs 深→浅重排）。 */
        private final List<Plan> gapNodes = new ArrayList<>();
        /** 命中的循环路径（编号序列，首尾相同）。 */
        private List<String> cyclePath = List.of();
        /** 直接先修要求（稳定顺序）。 */
        private List<Requirement> requirements = List.of();
        /** 每项要求选中的成员节点（ALL 只有一项），构成选定路线树。 */
        private final List<Plan> chosenPrereqs = new ArrayList<>();

        public Long getCourseId() {
            return courseId;
        }

        public boolean isPlaceholder() {
            return placeholder;
        }

        public boolean isExists() {
            return exists;
        }

        public boolean isOpen() {
            return open;
        }

        public boolean isCompleted() {
            return completed;
        }

        public boolean isLearning() {
            return learning;
        }

        public String getCourseNo() {
            return courseNo;
        }

        public String getTitle() {
            return title;
        }

        public boolean isSatisfied() {
            return satisfied;
        }

        public boolean isViable() {
            return viable;
        }

        public boolean isCyclic() {
            return cyclic;
        }

        public boolean isDataGap() {
            return dataGap;
        }

        public boolean isClosedUnfinished() {
            return closedUnfinished;
        }

        public int getDepth() {
            return depth;
        }

        public Set<Long> getGapIds() {
            return gapIds;
        }

        public List<Plan> getGapNodes() {
            return gapNodes;
        }

        public List<String> getCyclePath() {
            return cyclePath;
        }

        public List<Requirement> getRequirements() {
            return requirements;
        }

        public List<Plan> getChosenPrereqs() {
            return chosenPrereqs;
        }
    }

    private final Map<Long, LessonCourse> courseMap;
    private final Set<Long> completedIds;
    private final Set<Long> learningIds;
    private final Map<Long, List<Requirement>> requirementMap = new LinkedHashMap<>();

    public CoursePathResolver(PathSnapshot snapshot) {
        this.courseMap = snapshot.courseMap();
        this.completedIds = nullToEmpty(snapshot.completed());
        this.learningIds = nullToEmpty(snapshot.learning());
        indexPrerequisites(snapshot.prerequisites());
    }

    private static Set<Long> nullToEmpty(Set<Long> ids) {
        return ids == null ? Set.of() : ids;
    }

    private void indexPrerequisites(List<CoursePrerequisite> rows) {
        Map<Long, List<CoursePrerequisite>> byCourse = new LinkedHashMap<>();
        for (CoursePrerequisite row : rows) {
            byCourse.computeIfAbsent(row.getCourseId(), k -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<Long, List<CoursePrerequisite>> entry : byCourse.entrySet()) {
            List<CoursePrerequisite> sorted = new ArrayList<>(entry.getValue());
            // 先 ALL 后 ELECTIVE；组按 groupNo；组内按 prereqId —— 要求顺序稳定，结论才可重复
            sorted.sort(Comparator
                    .comparing((CoursePrerequisite r) -> "ELECTIVE".equals(r.getRequireType()) ? 1 : 0)
                    .thenComparing(r -> r.getGroupNo() == null ? Integer.MAX_VALUE : r.getGroupNo())
                    .thenComparing(CoursePrerequisite::getPrereqId));
            Map<Integer, Requirement> groups = new LinkedHashMap<>();
            List<Requirement> alls = new ArrayList<>();
            for (CoursePrerequisite row : sorted) {
                if ("ELECTIVE".equals(row.getRequireType()) && row.getGroupNo() != null) {
                    groups.computeIfAbsent(row.getGroupNo(), g -> new Requirement(true, g))
                            .getPrereqIds().add(row.getPrereqId());
                } else {
                    Requirement single = new Requirement(false, null);
                    single.getPrereqIds().add(row.getPrereqId());
                    alls.add(single);
                }
            }
            List<Requirement> merged = new ArrayList<>(alls);
            merged.addAll(groups.values());
            requirementMap.put(entry.getKey(), merged);
        }
    }

    /** 解析目标课程；targetId 在课程表中不存在时返回占位节点的数据缺口结论。 */
    public Plan resolve(Long targetId) {
        return dfs(targetId, new LinkedHashSet<>(), new LinkedHashMap<>());
    }

    private Plan dfs(Long id, Set<Long> stack, Map<Long, Plan> memo) {
        Plan cached = memo.get(id);
        if (cached != null) {
            if (stack.contains(id)) {
                // 回到遍历栈上的节点 = 发现闭合环（含自指）：在回边目标处拼一次可读循环路径并抛出，
                // 沿入环路径的每个父节点在 catch 中标记 cyclic，遍历在环上终止，绝不无限递归。
                if (cached.cyclePath.isEmpty()) {
                    cached.cyclic = true;
                    cached.viable = false;
                    cached.satisfied = false;
                    cached.failRank = FAIL_CYCLE;
                    cached.cyclePath = buildCyclePath(stack, id);
                }
                throw new CycleException(cached);
            }
            return cached;
        }

        Plan node = new Plan();
        node.courseId = id;
        LessonCourse course = courseMap.get(id);
        if (course == null) {
            // 关联课程缺失：占位节点，任何路线走到这里都是数据缺口
            node.placeholder = true;
            node.exists = false;
            node.open = false;
            node.viable = false;
            node.dataGap = true;
            node.failRank = FAIL_DATA_GAP;
            node.courseNo = "C-?" + id;
            node.title = "缺失课程（id=" + id + "）";
            node.depth = 1;
            node.gapIds.add(id);
            node.gapNodes.add(node);
            memo.put(id, node);
            return node;
        }

        node.exists = true;
        node.open = "OPEN".equals(course.getStatus());
        node.closedUnfinished = !node.open;
        node.courseNo = course.getCourseNo();
        node.title = course.getTitle();
        node.completed = completedIds.contains(id);
        node.learning = !node.completed && learningIds.contains(id);
        memo.put(id, node);

        List<Requirement> reqs = requirementMap.getOrDefault(id, List.of());
        node.requirements = reqs;

        // 已完成：子树整条满足，不再展开（下游有环 / 缺口也与当前学员无关）；
        // 已停开但已完成仍可作为先修解锁后继课程
        if (node.completed) {
            node.satisfied = true;
            node.viable = true;
            node.closedUnfinished = false;
            node.depth = 0;
            return node;
        }

        Set<Long> nextStack = new LinkedHashSet<>(stack);
        nextStack.add(id);

        if (reqs.isEmpty()) {
            // 无任何先修关系：默认可学；自身未完成，是深 1 的缺口叶子
            node.satisfied = true;
            node.viable = true;
            node.depth = 1;
            addSelfGap(node);
            return node;
        }

        boolean allSatisfied = true;
        boolean allViable = true;
        int worstRank = FAIL_SOFT;
        int maxDepth = 0;
        for (Requirement req : reqs) {
            List<Plan> candidates = new ArrayList<>();
            for (Long prereqId : req.prereqIds) {
                Plan child;
                try {
                    child = dfs(prereqId, nextStack, memo);
                } catch (CycleException ex) {
                    // 重新进入遍历栈上的节点（子树成环）：ex.hit 已在回边目标处标记为循环阻断。
                    // 不向上抛异常 —— 让环上节点带着 cyclic 标记正常返回，
                    // 多级祖先（如 17）经由选中成员的 cyclic 标记自然获知并终止该路线。
                    child = ex.hit;
                }
                candidates.add(child);
            }

            // 先在可走成员里择优（已完成的成员缺口 0、层级 0，天然胜出）
            Plan chosen = candidates.stream()
                    .filter(Plan::isViable)
                    .min(this::compareRoute)
                    .orElseGet(() -> candidates.stream().min(this::compareHardBlock).orElse(null));
            if (chosen == null) {
                allViable = false;
                allSatisfied = false;
                continue;
            }
            node.chosenPrereqs.add(chosen);
            maxDepth = Math.max(maxDepth, chosen.depth);
            mergeRoute(node, chosen);

            // 只有「已完成」的选中成员才算满足这项要求：成员自身可学但尚未完成不能解锁后继
            if (!chosen.completed) {
                allSatisfied = false;
            }
            if (!chosen.viable) {
                allViable = false;
                worstRank = Math.max(worstRank, chosen.failRank);
                if (chosen.cyclic) {
                    node.cyclic = true;
                    if (node.cyclePath.isEmpty()) {
                        node.cyclePath = chosen.cyclePath;
                    }
                }
                if (chosen.dataGap) {
                    node.dataGap = true;
                }
                if (chosen.closedUnfinished) {
                    node.closedUnfinished = true;
                }
            }
        }

        node.satisfied = allSatisfied;
        node.viable = allViable && node.exists && (node.open || node.completed);
        node.depth = maxDepth + 1;
        addSelfGap(node);

        if (!node.viable) {
            if (node.cyclic) {
                node.failRank = FAIL_CYCLE;
            } else if (node.dataGap) {
                node.failRank = FAIL_DATA_GAP;
            } else if (node.closedUnfinished) {
                node.failRank = FAIL_CLOSED;
            } else {
                node.failRank = FAIL_SOFT;
            }
        }
        return node;
    }

    /** 把选中成员的缺口并入当前节点（按 id 去重 —— 等价组 / 共享先修都不能重复计数）。 */
    private void mergeRoute(Plan node, Plan child) {
        for (Plan p : child.gapNodes) {
            if (!node.gapIds.contains(p.courseId)) {
                node.gapIds.add(p.courseId);
                node.gapNodes.add(p);
            }
        }
    }

    private void addSelfGap(Plan node) {
        if (!node.gapIds.contains(node.courseId)) {
            node.gapIds.add(node.courseId);
            node.gapNodes.add(node);
        }
    }

    /** 可走成员择优：缺失课程数少 → 缺口层级浅 → 课程编号小。 */
    private int compareRoute(Plan a, Plan b) {
        int byGap = Integer.compare(a.gapIds.size(), b.gapIds.size());
        if (byGap != 0) {
            return byGap;
        }
        int byDepth = Integer.compare(a.depth, b.depth);
        if (byDepth != 0) {
            return byDepth;
        }
        return courseOrder(a, b);
    }

    /** 全部硬阻断时选最可读的成员：循环 > 数据缺口 > 停开 > 软受阻，再按编号。 */
    private int compareHardBlock(Plan a, Plan b) {
        int byRank = Integer.compare(b.failRank, a.failRank);
        if (byRank != 0) {
            return byRank;
        }
        return courseOrder(a, b);
    }

    private int courseOrder(Plan a, Plan b) {
        int cmp = a.courseNo.compareTo(b.courseNo);
        return cmp != 0 ? cmp : Long.compare(a.courseId, b.courseId);
    }

    /**
     * 依遍历栈拼闭合循环路径，如 [C-6013, C-6014, C-6013]。
     * 触发回边时 stack 是父节点 17 的 nextStack=[...,13]，被重新进入的节点 id=13；
     * 自指时父栈 [12]、id=12。统一截取栈中从回边目标开始的后缀 + 回边目标收尾。
     */
    private List<String> buildCyclePath(Set<Long> parentStack, Long backId) {
        List<String> path = new ArrayList<>();
        boolean started = false;
        for (Long ancestorId : parentStack) {
            if (ancestorId.equals(backId)) {
                started = true;
            }
            if (started) {
                LessonCourse c = courseMap.get(ancestorId);
                path.add(c == null ? "C-?" + ancestorId : c.getCourseNo());
            }
        }
        LessonCourse back = courseMap.get(backId);
        path.add(back == null ? "C-?" + backId : back.getCourseNo());
        return path;
    }

    /** 遍历碰到栈上节点：抛出环上节点用于标记，路径由父节点拼接。 */
    @SuppressWarnings("serial")
    private static final class CycleException extends RuntimeException {
        private final Plan hit;

        private CycleException(Plan hit) {
            super(null, null, false, false);
            this.hit = hit;
        }
    }
}
