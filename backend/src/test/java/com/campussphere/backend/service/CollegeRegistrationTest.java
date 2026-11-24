package com.campussphere.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.campussphere.backend.service.CollegeRegistrationService.*;
import com.campussphere.backend.security.ApiError;

class CollegeRegistrationTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PlatformAccess access=mock(PlatformAccess.class);
    final PasswordEncoder encoder=mock(PasswordEncoder.class);
    final CollegeRegistrationService service=new CollegeRegistrationService(jdbc,access,mock(PlatformCollegeService.class),encoder);

    @Test void submissionHashesAndOnlyInsertsApplication() {
        String password="synthetic-local-only";
        when(encoder.encode(password)).thenReturn("encoded-fixture");
        service.submit(new Application(" Alpha ","alpha",null," Representative ","ADMIN@example.invalid"," Dean ",password));
        verify(jdbc).update(startsWith("UPDATE public.college_registration_requests SET status='EXPIRED',password_hash=NULL"));
        verify(jdbc).update(startsWith("INSERT INTO public.college_registration_requests"),eq("Alpha"),eq("ALPHA"),
            isNull(),eq("Representative"),eq("admin@example.invalid"),eq("Dean"),eq("encoded-fixture"),eq("ALPHA"),eq("admin@example.invalid"));
        verifyNoMoreInteractions(jdbc);
    }
    @Test void reviewReadsOnlyAuthorizedSnapshotAndProjectsOverdueStatus() throws Exception {
        var actor = new com.campussphere.backend.security.AccountAccessPolicy.Account(
                7L, true, false, java.util.Set.of("SUPER_ADMIN"), null);
        var reader = new CollegeRegistrationService(jdbc, new PlatformAccess(jdbc),
                mock(PlatformCollegeService.class), encoder);
        var row = new Details(4,"Alpha","ALPHA",null,"Rep","rep@example.invalid","Dean","EXPIRED",
                java.time.OffsetDateTime.parse("2026-11-24T00:00:00Z"));
        String projection = "CASE WHEN status='PENDING' AND expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE status END";
        when(jdbc.query(startsWith("SELECT is_active"), any(org.springframework.jdbc.core.RowMapper.class), eq(7L)))
                .thenReturn(java.util.List.of(true));
        when(jdbc.queryForList(startsWith("SELECT ur.role_id"), eq(Long.class), eq(7L)))
                .thenReturn(java.util.List.of(1L));
        when(jdbc.queryForObject(contains(projection),eq(Long.class),eq("EXPIRED"))).thenReturn(1L);
        when(jdbc.query(contains(projection), any(org.springframework.jdbc.core.RowMapper.class),eq("EXPIRED"),eq(0L)))
                .thenReturn(java.util.List.of(row));
        when(jdbc.query(contains(projection), any(org.springframework.jdbc.core.RowMapper.class),eq(4L)))
                .thenReturn(java.util.List.of(row));
        assertEquals(new Page(1,java.util.List.of(row)), reader.list(actor,0,"EXPIRED"));
        assertEquals(row, reader.detail(actor,4));
        var calls = mockingDetails(jdbc).getInvocations();
        assertEquals(7,calls.size()); // Two authorization reads per endpoint, plus count, page and detail.
        for (var call : calls) {
            String sql = ((String)call.getArgument(0)).toUpperCase(java.util.Locale.ROOT);
            assertTrue(sql.startsWith("SELECT "));
            assertFalse(sql.matches("(?s).*\\\\b(UPDATE|DELETE|INSERT|SHARE|PASSWORD_HASH|PRIVILEGED_AUDIT_EVENTS)\\\\b.*"));
        }
        for (var method : CollegeRegistrationService.class.getDeclaredMethods()) {
            if (java.util.Set.of("list","detail").contains(method.getName())) {
                assertTrue(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
            }
        }
    }

    @Test void twoSweepInvocationsOnlyTransitionOverduePendingRows() {
        String sql = "UPDATE public.college_registration_requests SET status='EXPIRED',password_hash=NULL,decided_at=expires_at " +
                "WHERE status='PENDING' AND expires_at<=clock_timestamp()";
        // Model the affected-row counts of workers after the database serializes their updates.
        when(jdbc.update(sql)).thenReturn(1,0);
        service.expire();
        service.expire();
        verify(jdbc,times(2)).update(sql);
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(access);
    }

    @Test void rejectsOversizeUtf8BeforeHashing() {
        assertThrows(ApiError.Failure.class,()->service.submit(new Application("A","A",null,"A","a@example.invalid","Dean","é".repeat(37))));
        verifyNoInteractions(encoder,jdbc);
    }
    @Test void refusesIncompleteVerificationBeforeLoadingRequest() {
        assertThrows(ApiError.Failure.class,()->service.approve(null,1,new Evidence("OFFICIAL_EMAIL",true,true,false)));
        verifyNoInteractions(jdbc);
    }
}