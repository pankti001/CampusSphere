package com.campussphere.backend.service;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.aop.framework.ProxyFactory;
import com.campussphere.backend.dto.CollegeInput;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.CollegeRegistrationService.*;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"unchecked","deprecation"})
class CollegeRegistrationDecisionTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PlatformAccess access=mock(PlatformAccess.class);
    final PlatformCollegeService colleges=mock(PlatformCollegeService.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final SimpleTransactionStatus tx=new SimpleTransactionStatus();
    final Evidence evidence=new Evidence("OFFICIAL_CALLBACK",true,true,true);
    final OffsetDateTime expiry = OffsetDateTime.parse("2026-11-24T12:00:00Z");
    CollegeRegistrationService service;

    @BeforeEach void setup() {
        var target=new CollegeRegistrationService(jdbc,access,colleges,mock(PasswordEncoder.class));
        var proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        service=(CollegeRegistrationService)proxy.getProxy();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(tx);
        when(access.requireCurrentSuperAdmin(null)).thenReturn(7L);
        row("PENDING");
        when(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)).thenReturn(expiry.minusNanos(1000));
        when(colleges.create(null,new CollegeInput("EX","Example"))).thenReturn(
            new PlatformCollegeService.CollegeDetails(3,"EX","Example",false,null,null));
        when(jdbc.queryForList(startsWith("INSERT INTO public.users"),eq(Long.class),eq(3L),eq(1L))).thenReturn(List.of(9L));
        when(jdbc.update(contains("WHERE name='COLLEGE_ADMIN'"),eq(9L))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE public.college_registration_requests SET status='APPROVED',password_hash=NULL"),
            eq(7L),eq(evidence.method()),eq(3L),eq(9L),eq(1L))).thenReturn(1);
    }
    void row(String status) {
        when(jdbc.query(endsWith("FOR UPDATE"),any(RowMapper.class),eq(1L))).thenReturn(List.of(
            new Details(1,"Example","EX",null,"Rep","rep@example.invalid","Dean",status,expiry)));
    }

    @Test void approvesWithCollegeBindingSingleGrantAndAudit() {
        service.approve(null,1,evidence);
        verify(colleges).status(null,3,true);
        verify(jdbc,times(1)).update(contains("WHERE name='COLLEGE_ADMIN'"),eq(9L));
        verify(access).audit(7,"COLLEGE_REGISTRATION_APPROVED",1);
        verify(transactions).commit(tx);
    }
    @Test void auditFailureRollsBackTransaction() {
        doThrow(new IllegalStateException("synthetic audit failure")).when(access).audit(7,"COLLEGE_REGISTRATION_APPROVED",1);
        assertThrows(IllegalStateException.class,()->service.approve(null,1,evidence));
        verify(transactions).rollback(tx);
        verify(transactions,never()).commit(any());
    }
    @Test void recheckAfterRowLockRefusesSecondDecision() {
        row("APPROVED");
        assertThrows(ApiError.Failure.class,()->service.reject(null,1,new Rejection("Synthetic reason")));
        verifyNoInteractions(colleges);
        verify(access,never()).audit(anyLong(),anyString(),anyLong());
    }
    @Test void expiryBoundaryDeniesApprovalBeforeCollegeCreation() {
        when(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)).thenReturn(expiry, expiry.plusNanos(1000));
        assertThrows(ApiError.Failure.class,()->service.approve(null,1,evidence));
        assertThrows(ApiError.Failure.class,()->service.approve(null,1,evidence));
        verifyNoInteractions(colleges);
    }
    @Test void secondApprovalAfterWinnerCannotCreateAnotherCollege() {
        service.approve(null, 1, evidence);
        row("APPROVED"); // Model the status observed after the second worker obtains the lock.
        assertThrows(ApiError.Failure.class, () -> service.approve(null, 1, evidence));
        verify(colleges, times(1)).create(null, new CollegeInput("EX","Example"));
        verify(access, times(1)).audit(7,"COLLEGE_REGISTRATION_APPROVED",1);
    }

    @Test void finalDeadlineCheckRollsBackApproval() {
        when(jdbc.update(contains("expires_at>statement_timestamp()"),eq(7L),eq(evidence.method()),
                eq(3L),eq(9L),eq(1L))).thenReturn(0);
        assertThrows(ApiError.Failure.class, () -> service.approve(null, 1, evidence));
        verify(transactions).rollback(tx);
        verify(access, never()).audit(anyLong(),anyString(),anyLong());
    }

    @Test void existingEmailPreventsCreation() {
        when(jdbc.queryForObject(startsWith("SELECT EXISTS"),eq(Boolean.class),eq("rep@example.invalid"))).thenReturn(true);
        assertThrows(ApiError.Failure.class,()->service.approve(null,1,evidence));
        verifyNoInteractions(colleges);
    }
    @Test void racingEmailInsertRollsBackNewCollege() {
        when(jdbc.queryForList(startsWith("INSERT INTO public.users"),eq(Long.class),eq(3L),eq(1L))).thenReturn(List.of());
        assertThrows(ApiError.Failure.class,()->service.approve(null,1,evidence));
        verify(transactions).rollback(tx);
    }
    @Test void codeConflictStopsApproval() {
        when(colleges.create(null,new CollegeInput("EX","Example"))).thenThrow(ApiError.COLLEGE_CODE_CONFLICT.exception());
        assertThrows(ApiError.Failure.class,()->service.approve(null,1,evidence));
        verify(transactions).rollback(tx);
    }
    @Test void rejectionClearsHashAndAuditsInTransaction() {
        when(jdbc.update(startsWith("UPDATE public.college_registration_requests SET status='REJECTED',password_hash=NULL"),
            eq(7L),eq("Evidence insufficient"),eq(1L))).thenReturn(1);
        service.reject(null,1,new Rejection("Evidence insufficient"));
        verify(access).audit(7,"COLLEGE_REGISTRATION_REJECTED",1);
        verify(transactions).commit(tx);
        verifyNoInteractions(colleges);
    }
}