package com.music.instrument.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.music.instrument.common.BizException;
import com.music.instrument.domain.CoursePrereqGroup;
import com.music.instrument.domain.CoursePrerequisite;
import com.music.instrument.domain.Enrollment;
import com.music.instrument.domain.LessonCourse;
import com.music.instrument.dto.view.CoursePathView;
import com.music.instrument.dto.view.GapCourse;
import com.music.instrument.dto.view.PrereqGroupView;
import com.music.instrument.dto.view.PrereqItemView;
import com.music.instrument.mapper.CoursePrereqGroupMapper;
import com.music.instrument.mapper.CoursePrerequisiteMapper;
import com.music.instrument.mapper.EnrollmentMapper;
import com.music.instrument.mapper.LessonCourseMapper;

/**
 * 学员课程路径统一解析。
 *
 * 口径（任何入口都必须走这里，禁止各接口只查直接上一层）：
 *  1. 关系图一次性批量读：全部课程 + 全部先修组 + 全部先修项；某学员全部路径只再加一条
 *     约课记录查询，SELECT 总数是常数，不随课程数量、依赖深度增长。
 *  2. 只有 COMPLETED 已完成记录能解锁；正在学习 / 已取消 / 历史状态缺失（NULL 或未知码）
 *     一律按未完成处理。约课记录关联的学员/课程缺失时保留占位，该路线标数据缺口。
 *  3. 等价先修组（ANY）组内完成任意一门即整组满足，并集去重，同一门课不在缺口里重复计数。
 *  4. 多条可行路线择优：缺失课程最少 → 缺失层级最浅 → 路线入口课程编号最小。
 *  5. 自指 / 环路立即终止遍历，给出可读循环路径（C-x → C-y → C-x），不无限递归。
 *  6. 已停开课程：历史已完成仍可解锁后续；未完成则是一条不可行路线（停开不能新学），
 *     但不作为数据缺口。
 *  7. 没有任何先修关系的课程默认可学。
 */
@Service
public class CoursePathService {

    static final String VERDICT_AVAILABLE = "AVAILABLE";
    static final String VERDICT_BLOCKED = "BLOCKED";
    static final String VERDICT_CYCLE = "CYCLE";

    private static final String MODE_ALL = "ALL";
    private static final String MODE_ANY = "ANY";

    private final LessonCourseMapper lessonCourseMapper;
    private final CoursePrereqGroupMapper prereqGroupMapper;
    private final CoursePrerequisiteMapper prerequisiteMapper;
    private final EnrollmentMapper enrollmentMapper;

    public CoursePathService(LessonCourseMapper lessonCourseMapper,
                             CoursePrereqGroupMapper prereqGroupMapper,
                             CoursePrerequisiteMapper prerequisiteMapper,
                             EnrollmentMapper enrollmentMapper) {
        this.lessonCourseMapper = lessonCourseMapper;
        this.prereqGroupMapper = prereqGroupMapper;
        this.prerequisiteMapper = prerequisiteMapper;
        this.enrollmentMapper = enrollmentMapper;
    }

    // =====================================================================
    // 对外入口
    // =====================================================================

    /**
     * 一次查看某学员的全部课程路径：4 条查询（课程/组/项/约课记录），规模恒定。
     */
    public List<CoursePathView> resolveAll(Long studentId) {
        if (studentId == null) {
            throw new BizException("请先选择学员");
        }
        return resolveAll(studentId, lessonCourseMapper.findAll());
    }

    /**
     * 用调用方已批量读入的课程列表解析全部路径：列表接口复用课程数据，不重复查询。
     * 组/项/约课记录仍各只批量读一次。
     */
    public List<CoursePathView> resolveAll(Long studentId, List<LessonCourse> courses) {
        if (studentId == null) {
            throw new BizException("请先选择学员");
        }
        Graph graph = loadGraph(courses);
        Map<Long, String> learningStates = learningStates(enrollmentMapper.listPlainByStudentId(studentId));
        Evaluator evaluator = new Evaluator(graph, learningStates);
        List<CoursePathView> views = new ArrayList<>();
        for (LessonCourse course : courses) {
            views.add(evaluator.toView(course.getId()));
        }
        return views;
    }

    /** 单门课程路径（约课校验、课程详情、单查接口共用）。 */
    public CoursePathView resolve(Long studentId, Long courseId) {
        if (studentId == null) {
            throw new BizException("请先选择学员");
        }
        if (courseId == null) {
            throw new BizException("请指定要查看路径的课程");
        }
        List<LessonCourse> courses = lessonCourseMapper.findAll();
        LessonCourse target = courses.stream().filter(c -> c.getId().equals(courseId)).findFirst().orElse(null);
        if (target == null) {
            throw new BizException("课程不存在，无法解析学习路径");
        }
        Graph graph = loadGraph(courses);
        Map<Long, String> learningStates = learningStates(enrollmentMapper.listPlainByStudentId(studentId));
        return new Evaluator(graph, learningStates).toView(courseId);
    }

    /**
     * 约课闸门：单个/批量入口复用同一判定。
     *
     * @param pendingCourseIds 本次请求正在约、尚未落库的课程；它们不能在同一批里互相充当已完成先修。
     * @throws BizException 任一目标课程先修不满足时，给出带最短缺口路径的中文原因
     */
    public void assertEnrollable(Long studentId, List<Long> targetCourseIds, Set<Long> pendingCourseIds) {
        if (studentId == null || targetCourseIds == null || targetCourseIds.isEmpty()) {
            return;
        }
        List<LessonCourse> courses = lessonCourseMapper.findAll();
        Graph graph = loadGraph(courses);
        Map<Long, String> states = learningStates(enrollmentMapper.listPlainByStudentId(studentId));
        // 批量内的课程一律视为「没有已完成记录」，避免同批互相解锁
        if (pendingCourseIds != null) {
            pendingCourseIds.forEach(id -> states.put(id, MusicDict.LEARNING_NONE));
        }
        Evaluator evaluator = new Evaluator(graph, states);
        for (Long courseId : targetCourseIds) {
            CoursePathView view = evaluator.toView(courseId);
            if (VERDICT_AVAILABLE.equals(view.getVerdict())) {
                continue;
            }
            if (VERDICT_CYCLE.equals(view.getVerdict())) {
                throw new BizException("课程【" + label(graph, courseId) + "】的先修关系存在环路："
                        + view.getCyclePath() + "，请先修正课程关系");
            }
            throw new BizException("课程【" + label(graph, courseId) + "】先修未完成，最短缺口路径："
                    + formatGapPath(view.getGapPath()) + "（缺 " + view.getMissingCount() + " 门先修）");
        }
    }

    private String label(Graph graph, Long courseId) {
        LessonCourse course = graph.courses.get(courseId);
        return course == null ? "课程#" + courseId : course.getCourseNo() + " " + course.getTitle();
    }

    private String formatGapPath(List<GapCourse> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return "存在数据缺口，请联系教务修正课程关系";
        }
        List<String> labels = new ArrayList<>();
        for (GapCourse gap : gaps) {
            labels.add(gap.isMissing() ? "已删除课程#" + gap.getCourseId()
                    : gap.getCourseNo() + " " + gap.getTitle());
        }
        return String.join(" → ", labels);
    }

    // =====================================================================
    // 关系图加载（批量、只允许全表读）
    // =====================================================================

    private Graph loadGraph(List<LessonCourse> courses) {
        List<CoursePrereqGroup> groups = prereqGroupMapper.findAll();
        List<CoursePrerequisite> prerequisites = prerequisiteMapper.findAll();

        Map<Long, LessonCourse> courseMap = new LinkedHashMap<>();
        for (LessonCourse course : courses) {
            courseMap.put(course.getId(), course);
        }
        Map<Long, List<CoursePrereqGroup>> groupsByCourse = new LinkedHashMap<>();
        Map<Long, CoursePrereqGroup> groupMap = new LinkedHashMap<>();
        for (CoursePrereqGroup group : groups) {
            groupsByCourse.computeIfAbsent(group.getCourseId(), k -> new ArrayList<>()).add(group);
            groupMap.put(group.getId(), group);
        }
        Map<Long, List<Long>> prereqIdsByGroup = new LinkedHashMap<>();
        for (CoursePrerequisite prerequisite : prerequisites) {
            prereqIdsByGroup.computeIfAbsent(prerequisite.getGroupId(), k -> new ArrayList<>())
                    .add(prerequisite.getPrerequisiteId());
        }
        return new Graph(courseMap, groupsByCourse, groupMap, prereqIdsByGroup);
    }

    /**
     * 约课记录归一为 courseId -> 学习状态。同一学员同一课程只有一条记录（唯一键）。
     * status 为 NULL/未知码统一归为「状态缺失」；关联课程缺失的记录也保留，由解析层标占位。
     */
    private Map<Long, String> learningStates(List<Enrollment> enrollments) {
        Map<Long, String> states = new LinkedHashMap<>();
        for (Enrollment enrollment : enrollments) {
            states.put(enrollment.getCourseId(), MusicDict.toLearningState(enrollment.getStatus()));
        }
        return states;
    }

    private record Graph(Map<Long, LessonCourse> courses,
                         Map<Long, List<CoursePrereqGroup>> groupsByCourse,
                         Map<Long, CoursePrereqGroup> groupMap,
                         Map<Long, List<Long>> prereqIdsByGroup) {
    }

    // =====================================================================
    // 解析器：一次解析内共享 memo 与环路染色，深层依赖/共享节点不重复算
    // =====================================================================

    private final class Evaluator {

        private final Graph graph;
        private final Map<Long, String> learningStates;

        /** 课程 id -> 解析结论（仅黑白节点共享）；灰节点（当前 DFS 栈上）不写 memo。 */
        private final Map<Long, Outcome> memo = new LinkedHashMap<>();
        /** 全局已确认落在环上的课程 id：任何通向它们的路线都不可行。 */
        private final Set<Long> cyclic = new LinkedHashSet<>();

        private Evaluator(Graph graph, Map<Long, String> learningStates) {
            this.graph = graph;
            this.learningStates = learningStates;
        }

        /**
         * 一条路线的解析结论（X 是「被别人要求的一门先修课」）。
         * feasible=false：路线走不通（环路 / 关联缺失 / 停开未完成）。
         * gaps：沿路线仍未完成的课程并集 —— 只要 X 本人没完成，X 自己也在缺口里
         *       （在学/取消/状态缺失都算未完成，不能替父课程解锁），已去重。
         * height：缺口链课程数（叶子未完成课为 1），同缺失数时比它（越小越浅）。
         * chosen：ANY 组择优时选中链路上的课程 id（先修在前、自身在末尾），用于稳定产出缺口路径。
         */
        private record Outcome(boolean feasible,
                               LinkedHashSet<Long> gaps,
                               int height,
                               List<Long> chosen) {

            static Outcome infeasible(LinkedHashSet<Long> gaps, int height, List<Long> chosen) {
                return new Outcome(false, gaps, height, chosen);
            }

            static Outcome feasible(LinkedHashSet<Long> gaps, int height, List<Long> chosen) {
                return new Outcome(true, gaps, height, chosen);
            }
        }

        /** 目标课程视角的组聚合：不含目标自身（目标不是自己的先修）。 */
        private record RootAggregate(boolean feasible,
                                     LinkedHashSet<Long> gaps,
                                     int height,
                                     List<Long> chosen) {
        }

        CoursePathView toView(Long courseId) {
            LessonCourse course = graph.courses.get(courseId);
            // 先做一次全量递归染色，让环路集合与各节点 memo 就绪
            RootAggregate root = aggregateRoot(courseId, new LinkedHashSet<>());

            CoursePathView view = new CoursePathView();
            view.setCourseId(courseId);
            if (course != null) {
                view.setCourseNo(course.getCourseNo());
                view.setTitle(course.getTitle());
                view.setClosed(!"OPEN".equals(course.getStatus()));
            }
            String state = learningStates.getOrDefault(courseId, MusicDict.LEARNING_NONE);
            view.setLearningState(state);
            view.setLearningStateName(MusicDict.learningStateName(state));

            List<PrereqGroupView> groupViews = buildGroupViews(courseId);
            view.setGroups(groupViews);
            boolean groupDataGap = groupViews.stream().anyMatch(PrereqGroupView::isDataGap);
            view.setDataGap(groupDataGap);

            // 目标自身 / 任何上游命中环路：终止并给出可读循环路径
            String cyclePath = describeCycleIfAny(courseId);
            if (cyclePath != null) {
                view.setVerdict(VERDICT_CYCLE);
                view.setVerdictName("循环");
                view.setCyclePath(cyclePath);
                view.setGapPath(List.of());
                view.setMissingCount(0);
                view.setMissingLevel(0);
                view.setSummary("先修关系存在环路：" + cyclePath + "，已终止解析，请修正课程关系");
                return view;
            }

            // 缺口路径：chosen 链（先修在前、去重）末尾再挂上目标课程自身
            List<GapCourse> gapPath = new ArrayList<>();
            Set<Long> shown = new LinkedHashSet<>();
            for (Long id : root.chosen()) {
                if (shown.add(id)) {
                    gapPath.add(toGapCourse(id));
                }
            }
            if (shown.add(courseId)) {
                gapPath.add(toGapCourse(courseId));
            }
            view.setGapPath(gapPath);
            view.setMissingCount(root.gaps().size());
            view.setMissingLevel(root.height());

            boolean selfCompleted = MusicDict.LEARNING_COMPLETED.equals(state);
            boolean selfClosed = course != null && !"OPEN".equals(course.getStatus());

            if (root.feasible() && root.gaps().isEmpty() && !selfClosed) {
                view.setVerdict(VERDICT_AVAILABLE);
                view.setVerdictName("可学");
                view.setSummary(groupViews.isEmpty()
                        ? "没有先修要求，可以直接学习"
                        : "全部先修已完成，可以学习");
            } else {
                view.setVerdict(VERDICT_BLOCKED);
                view.setVerdictName("受阻");
                List<String> reasons = new ArrayList<>();
                if (groupDataGap) {
                    reasons.add("存在数据缺口（先修课程或学习记录关联对象缺失）");
                }
                if (selfClosed && !selfCompleted) {
                    reasons.add("该课程已停开，仅作历史先修展示，不能新约");
                }
                if (!root.gaps().isEmpty()) {
                    reasons.add("最短缺口路径：" + gapLabels(gapPath));
                }
                if (reasons.isEmpty()) {
                    reasons.add("先修未完成");
                }
                view.setSummary(String.join("；", reasons));
            }
            return view;
        }

        // ---------- 核心递归 ----------

        /**
         * 计算某门课「作为先修」的结论：未完成的课自身一定计入缺口。
         */
        private Outcome evaluate(Long courseId, LinkedHashSet<Long> stack) {
            if (cyclic.contains(courseId)) {
                return Outcome.infeasible(new LinkedHashSet<>(), 0, List.of());
            }
            if (stack.contains(courseId)) {
                // 发现回边：从回边起点开始的栈内节点全部在环上，立即终止这条路线
                boolean inCycle = false;
                for (Long id : stack) {
                    if (id.equals(courseId)) {
                        inCycle = true;
                    }
                    if (inCycle) {
                        cyclic.add(id);
                    }
                }
                return Outcome.infeasible(new LinkedHashSet<>(), 0, List.of());
            }
            Outcome cached = memo.get(courseId);
            if (cached != null) {
                return cached;
            }

            // 只有明确的已完成记录能解锁：本人已完成 → 自身零缺口、可行
            String state = learningStates.get(courseId);
            if (MusicDict.LEARNING_COMPLETED.equals(state)) {
                Outcome done = Outcome.feasible(new LinkedHashSet<>(), 0, List.of());
                memo.put(courseId, done);
                return done;
            }

            LessonCourse course = graph.courses.get(courseId);
            // 关联课程缺失：保留占位，路线标数据缺口，永远不被自动满足
            if (course == null) {
                LinkedHashSet<Long> gaps = new LinkedHashSet<>();
                gaps.add(courseId);
                Outcome missing = Outcome.infeasible(gaps, 1, List.of(courseId));
                memo.put(courseId, missing);
                return missing;
            }

            stack.add(courseId);
            RootAggregate agg = aggregateGroups(courseId, stack);
            stack.remove(courseId);

            LinkedHashSet<Long> selfGaps = new LinkedHashSet<>(agg.gaps());
            selfGaps.add(courseId);
            List<Long> selfChosen = appendSelf(courseId, agg.chosen());
            boolean selfClosed = !"OPEN".equals(course.getStatus());
            if (!agg.feasible() || selfClosed) {
                // 上游走不通，或本人已停开未完成：整条路线不可行
                Outcome bad = Outcome.infeasible(selfGaps, agg.height() + 1, selfChosen);
                memo.put(courseId, bad);
                return bad;
            }
            // 本人未完成但先修可补齐：可行路线，本人也是缺口链的一环
            Outcome result = Outcome.feasible(selfGaps, agg.height() + 1, selfChosen);
            memo.put(courseId, result);
            return result;
        }

        /** 目标课程视角：聚合其全部先修组，但不把目标自身算进缺口。 */
        private RootAggregate aggregateRoot(Long courseId, LinkedHashSet<Long> stack) {
            stack.add(courseId);
            RootAggregate agg = aggregateGroups(courseId, stack);
            stack.remove(courseId);
            return agg;
        }

        /** 聚合一节课的全部先修组（ALL/ANY 都要满足），组间并集去重，高度取最大。 */
        private RootAggregate aggregateGroups(Long courseId, LinkedHashSet<Long> stack) {
            boolean feasible = true;
            LinkedHashSet<Long> gaps = new LinkedHashSet<>();
            List<Long> chosen = new ArrayList<>();
            int height = 0;
            for (CoursePrereqGroup group : sortedGroups(courseId)) {
                GroupResult result = evaluateGroup(group, stack);
                feasible &= result.feasible;
                gaps.addAll(result.gaps);
                chosen.addAll(result.chosen);
                height = Math.max(height, result.height);
            }
            return new RootAggregate(feasible, gaps, height, chosen);
        }

        private record GroupResult(boolean feasible, boolean dataGap,
                                   LinkedHashSet<Long> gaps, int height, List<Long> chosen) {
        }

        private List<CoursePrereqGroup> sortedGroups(Long courseId) {
            return graph.groupsByCourse().getOrDefault(courseId, List.of()).stream()
                    .sorted(Comparator.comparing(CoursePrereqGroup::getGroupNo)
                            .thenComparing(CoursePrereqGroup::getId))
                    .toList();
        }

        private GroupResult evaluateGroup(CoursePrereqGroup group, LinkedHashSet<Long> stack) {
            List<Long> prereqIds = graph.prereqIdsByGroup().getOrDefault(group.getId(), List.of());
            if (MODE_ANY.equals(group.getMatchMode())) {
                return evaluateAny(prereqIds, stack);
            }
            // ALL（含未知 mode，从严按全部完成处理）
            return evaluateAll(prereqIds, stack);
        }

        private GroupResult evaluateAll(List<Long> prereqIds, LinkedHashSet<Long> stack) {
            boolean feasible = true;
            boolean dataGap = false;
            LinkedHashSet<Long> gaps = new LinkedHashSet<>();
            List<Long> chosen = new ArrayList<>();
            int height = 0;
            for (Long prereqId : sortForStable(prereqIds)) {
                Outcome item = evaluate(prereqId, stack);
                feasible &= item.feasible();
                dataGap |= graph.courses.get(prereqId) == null
                        || cyclic.contains(prereqId)
                        || item.gaps().stream().anyMatch(id -> graph.courses.get(id) == null);
                gaps.addAll(item.gaps());
                chosen.addAll(item.chosen());
                height = Math.max(height, item.height());
            }
            return new GroupResult(feasible, dataGap, gaps, height, chosen);
        }

        /**
         * 等价先修组：先在「可行路线」里择优（缺失最少→层级最浅→入口课程编号最小）；
         * 所有路线都不可行时，才在不可行路线里按同一序选一条用于展示缺口。
         * 组一旦被某条路线满足，其余路线缺口不计入并集（等价组不重复计数）。
         */
        private GroupResult evaluateAny(List<Long> prereqIds, LinkedHashSet<Long> stack) {
            List<Long> ordered = sortForStable(prereqIds);
            List<Outcome> outcomes = new ArrayList<>();
            boolean dataGap = false;
            for (Long prereqId : ordered) {
                Outcome item = evaluate(prereqId, stack);
                outcomes.add(item);
                dataGap |= graph.courses.get(prereqId) == null
                        || cyclic.contains(prereqId)
                        || item.gaps().stream().anyMatch(id -> graph.courses.get(id) == null);
            }
            List<Outcome> feasiblePool = outcomes.stream().filter(Outcome::feasible).toList();
            boolean anyFeasible = !feasiblePool.isEmpty();
            List<Outcome> pool = anyFeasible ? feasiblePool : outcomes;
            Outcome best = pool.stream().min(this::compareRoutes).orElse(null);
            if (best == null) {
                // 空的等价组属于配置异常：按数据缺口处理，不静默放行
                return new GroupResult(false, true, new LinkedHashSet<>(), 0, List.of());
            }
            return new GroupResult(anyFeasible, dataGap,
                    new LinkedHashSet<>(best.gaps()), best.height(), new ArrayList<>(best.chosen()));
        }

        /** 缺失课程最少 → 层级最浅 → 路线最浅入口课程编号最小。 */
        private int compareRoutes(Outcome a, Outcome b) {
            int bySize = Integer.compare(a.gaps().size(), b.gaps().size());
            if (bySize != 0) {
                return bySize;
            }
            int byHeight = Integer.compare(a.height(), b.height());
            if (byHeight != 0) {
                return byHeight;
            }
            return routeEntryKey(a).compareTo(routeEntryKey(b));
        }

        /** 路线最浅入口（chosen 链第一门；链为空说明该候选本身已完成，取候选课程编号）。 */
        private String routeEntryKey(Outcome outcome) {
            if (!outcome.chosen().isEmpty()) {
                return courseOrderKey(outcome.chosen().get(0));
            }
            return "";
        }

        // ---------- 视图组装（与递归同一口径，只解释、不再判定） ----------

        private List<PrereqGroupView> buildGroupViews(Long courseId) {
            List<PrereqGroupView> views = new ArrayList<>();
            for (CoursePrereqGroup group : sortedGroups(courseId)) {
                PrereqGroupView groupView = new PrereqGroupView();
                groupView.setGroupId(group.getId());
                groupView.setGroupNo(group.getGroupNo());
                groupView.setMatchMode(MODE_ANY.equals(group.getMatchMode()) ? MODE_ANY : MODE_ALL);
                groupView.setMatchModeName(MusicDict.prereqMatchModeName(groupView.getMatchMode()));
                groupView.setRemark(group.getRemark());

                List<PrereqItemView> itemViews = new ArrayList<>();
                boolean anySatisfied = false;
                boolean allSatisfied = true;
                boolean dataGap = false;
                List<Long> prereqIds = sortForStable(
                        graph.prereqIdsByGroup().getOrDefault(group.getId(), List.of()));
                for (Long prereqId : prereqIds) {
                    Outcome item = evaluate(prereqId, new LinkedHashSet<>());
                    PrereqItemView itemView = new PrereqItemView();
                    itemView.setCourse(toGapCourse(prereqId));
                    String itemState = learningStates.getOrDefault(prereqId, MusicDict.LEARNING_NONE);
                    itemView.setLearningState(itemState);
                    itemView.setLearningStateName(MusicDict.learningStateName(itemState));
                    boolean satisfied = MusicDict.LEARNING_COMPLETED.equals(itemState);
                    itemView.setSatisfied(satisfied);
                    itemView.setBlocked(!item.feasible());
                    // 该项自身缺失、命中环路，或其缺口链上存在已删除课程 → 数据缺口路线
                    boolean itemGap = graph.courses.get(prereqId) == null
                            || cyclic.contains(prereqId)
                            || item.gaps().stream().anyMatch(id -> graph.courses.get(id) == null);
                    itemView.setDataGap(itemGap);
                    itemView.setCyclePath(cyclic.contains(prereqId) ? describeCycle(prereqId) : null);
                    itemViews.add(itemView);
                    anySatisfied |= satisfied;
                    allSatisfied &= satisfied;
                    dataGap |= itemGap;
                }
                boolean groupSatisfied = MODE_ANY.equals(group.getMatchMode())
                        ? anySatisfied
                        : allSatisfied && !itemViews.isEmpty();
                groupView.setItems(itemViews);
                groupView.setSatisfied(groupSatisfied);
                groupView.setDataGap(dataGap);
                views.add(groupView);
            }
            return views;
        }

        private GapCourse toGapCourse(Long courseId) {
            GapCourse gap = new GapCourse();
            gap.setCourseId(courseId);
            LessonCourse course = graph.courses.get(courseId);
            if (course == null) {
                gap.setMissing(true);
                gap.setStatusName("课程已删除");
                return gap;
            }
            gap.setCourseNo(course.getCourseNo());
            gap.setTitle(course.getTitle());
            gap.setTeacherName(course.getTeacherName());
            gap.setCategory(course.getCategory());
            gap.setPrice(course.getPrice());
            gap.setStatus(course.getStatus());
            gap.setStatusName(MusicDict.courseStatusName(course.getStatus()));
            gap.setClosed(!"OPEN".equals(course.getStatus()));
            return gap;
        }

        // ---------- 环路描述 ----------

        private String describeCycleIfAny(Long courseId) {
            if (cyclic.contains(courseId) || reachesCycle(courseId, new LinkedHashSet<>())) {
                return describeCycle(courseId);
            }
            return null;
        }

        private boolean reachesCycle(Long courseId, Set<Long> seen) {
            if (cyclic.contains(courseId)) {
                return true;
            }
            if (!seen.add(courseId)) {
                return false;
            }
            for (CoursePrereqGroup group : sortedGroups(courseId)) {
                for (Long prereqId : graph.prereqIdsByGroup().getOrDefault(group.getId(), List.of())) {
                    if (reachesCycle(prereqId, seen)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 沿「仍能回到环上」的最小课程编号边推进，给出从起点到再次撞上在栈节点的循环路径；
         * 同一环数据多次/多入口解析得到的路径稳定可读。
         */
        private String describeCycle(Long startId) {
            List<Long> path = new ArrayList<>();
            Set<Long> local = new LinkedHashSet<>();
            Long current = startId;
            while (current != null && !local.contains(current)) {
                local.add(current);
                path.add(current);
                current = smallestPrereqIntoCycle(current, local);
            }
            if (current != null) {
                path.add(current);
            }
            return String.join(" → ", path.stream().map(this::courseNoLabel).toList());
        }

        private Long smallestPrereqIntoCycle(Long courseId, Set<Long> local) {
            List<Long> prereqs = new ArrayList<>();
            for (CoursePrereqGroup group : sortedGroups(courseId)) {
                prereqs.addAll(graph.prereqIdsByGroup().getOrDefault(group.getId(), List.of()));
            }
            return prereqs.stream()
                    .filter(id -> cyclic.contains(id) || local.contains(id)
                            || reachesCycle(id, new LinkedHashSet<>()))
                    .min(Comparator.comparing(this::courseOrderKey))
                    .orElse(null);
        }

        // ---------- 工具 ----------

        private List<Long> sortForStable(List<Long> ids) {
            List<Long> copy = new ArrayList<>(new LinkedHashSet<>(ids));
            copy.sort(Comparator.comparing(this::courseOrderKey));
            return copy;
        }

        /** 课程编号字典序；编号缺失（课程已删除）排最后，再用 id 兜底，保证结论完全确定。 */
        private String courseOrderKey(Long courseId) {
            LessonCourse course = graph.courses.get(courseId);
            if (course == null || course.getCourseNo() == null) {
                return "￿" + String.format("%020d", courseId == null ? Long.MAX_VALUE : courseId);
            }
            return course.getCourseNo();
        }

        private String courseNoLabel(Long courseId) {
            LessonCourse course = graph.courses.get(courseId);
            return course == null ? "已删除课程#" + courseId : course.getCourseNo();
        }

        private List<Long> appendSelf(Long courseId, List<Long> chosen) {
            List<Long> merged = new ArrayList<>(chosen);
            if (!merged.contains(courseId)) {
                merged.add(courseId);
            }
            return merged;
        }

        private String gapLabels(List<GapCourse> gaps) {
            if (gaps.isEmpty()) {
                return "存在数据缺口";
            }
            return String.join(" → ", gaps.stream().map(g -> g.isMissing()
                    ? "已删除课程#" + g.getCourseId()
                    : g.getCourseNo() + " " + g.getTitle()).toList());
        }
    }
}
