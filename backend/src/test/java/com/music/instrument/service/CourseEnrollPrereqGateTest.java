package com.music.instrument.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.music.instrument.common.BizException;
import com.music.instrument.domain.LessonCourse;
import com.music.instrument.domain.Student;
import com.music.instrument.dto.BatchEnrollRequest;
import com.music.instrument.dto.EnrollmentCreateRequest;
import com.music.instrument.dto.view.CoursePathView;
import com.music.instrument.mapper.EnrollmentMapper;
import com.music.instrument.mapper.LessonCourseMapper;
import com.music.instrument.mapper.StudentMapper;

/**
 * 单个 / 批量约课复用同一条先修闸门：
 *  - 受阻时两个入口都收到同样口径的中文拒绝；
 *  - 批量同选中的课程不能互相充当已完成先修（pendingCourseIds 必须完整传入）。
 */
@ExtendWith(MockitoExtension.class)
class CourseEnrollPrereqGateTest {

    @Mock
    private StudentMapper studentMapper;
    @Mock
    private LessonCourseMapper lessonCourseMapper;
    @Mock
    private EnrollmentMapper enrollmentMapper;
    @Mock
    private CoursePathService coursePathService;

    @InjectMocks
    private CourseService courseService;

    private LessonCourse openCourse(long id, String no) {
        LessonCourse c = new LessonCourse();
        c.setId(id);
        c.setCourseNo(no);
        c.setTitle("课程" + no);
        c.setStatus("OPEN");
        c.setCapacity(10);
        c.setEnrolledCount(0);
        return c;
    }

    private Student student(long id, int balance) {
        Student s = new Student();
        s.setId(id);
        s.setName("学员" + id);
        s.setLessonBalance(balance);
        return s;
    }

    @Test
    void singleAndBatchRejectWithSameGateMessage() {
        when(studentMapper.findById(1L)).thenReturn(student(1, 10));
        when(lessonCourseMapper.findById(20L)).thenReturn(openCourse(20, "C-X"));
        when(enrollmentMapper.countByStudentAndCourse(1L, 20L)).thenReturn(0);
        when(lessonCourseMapper.findByIds(anyList())).thenReturn(List.of(openCourse(20, "C-X")));
        doAnswer(inv -> {
            throw new BizException("课程【C-X 课程C-X】先修未完成，最短缺口路径：C-1（缺 1 门先修）");
        }).when(coursePathService).assertEnrollable(eq(1L), anyList(), any());

        EnrollmentCreateRequest single = new EnrollmentCreateRequest();
        single.setStudentId(1L);
        single.setCourseId(20L);
        single.setLessons(1);
        BizException e1 = assertThrows(BizException.class, () -> courseService.enroll(single));
        assertTrue(e1.getMessage().contains("先修未完成"));

        BatchEnrollRequest batch = new BatchEnrollRequest();
        batch.setStudentId(1L);
        batch.setCourseIds(List.of(20L));
        batch.setLessons(1);
        BizException e2 = assertThrows(BizException.class, () -> courseService.batchEnroll(batch));
        // 同一闸门产出的原因
        assertTrue(e2.getMessage().contains("先修未完成"));
    }

    @Test
    void batchPassesAllSelectedIdsAsPendingSoTheyCannotUnlockEachOther() {
        when(studentMapper.findById(1L)).thenReturn(student(1, 100));
        when(lessonCourseMapper.findByIds(anyList()))
                .thenReturn(List.of(openCourse(20, "C-A"), openCourse(21, "C-B")));
        when(enrollmentMapper.countByStudentAndCourse(eq(1L), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(0);
        // 校验：批量必须把两门课都作为 pending 传入（同批互不当先修）
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Set<Long> pending = inv.getArgument(2);
            if (pending == null || !pending.contains(20L) || !pending.contains(21L)) {
                throw new AssertionError("必须把本批全部课程作为 pending 传入：" + pending);
            }
            return null;
        }).when(coursePathService).assertEnrollable(eq(1L), anyList(), any());

        BatchEnrollRequest batch = new BatchEnrollRequest();
        batch.setStudentId(1L);
        batch.setCourseIds(List.of(20L, 21L));
        batch.setLessons(1);
        // 闸门放行后继续走批量落库（mapper 默认 0 行，不关心后续）
        when(enrollmentMapper.insertBatch(anyList())).thenReturn(2);
        when(enrollmentMapper.list(eq(1L), eq(null))).thenReturn(List.of());
        courseService.batchEnroll(batch);
    }

    @Test
    void singleEnrollPassesEmptyPendingSet() {
        when(studentMapper.findById(1L)).thenReturn(student(1, 10));
        when(lessonCourseMapper.findById(20L)).thenReturn(openCourse(20, "C-X"));
        when(enrollmentMapper.countByStudentAndCourse(1L, 20L)).thenReturn(0);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Set<Long> pending = inv.getArgument(2);
            if (pending == null || !pending.isEmpty()) {
                throw new AssertionError("单个约课不应带 pending：" + pending);
            }
            return null;
        }).when(coursePathService).assertEnrollable(eq(1L), anyList(), any());
        // 后续落库返回视图即可（直接抛业务异常短路也接受：这里只验闸门入参）
        when(enrollmentMapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(enrollmentMapper.list(eq(1L), eq(null))).thenReturn(List.of());

        EnrollmentCreateRequest single = new EnrollmentCreateRequest();
        single.setStudentId(1L);
        single.setCourseId(20L);
        single.setLessons(1);
        courseService.enroll(single);
    }
}
