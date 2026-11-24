package com.campussphere.backend.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.campussphere.backend.dto.CollegeInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;

@Service
@Transactional
public class CollegeRegistrationService {
    public record Application(@NotBlank @Size(max=150) String name,
            @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_-]{0,29}") String code,
            @Size(max=250) @Pattern(regexp="https?://[^\\s/@?#]+(?:/[^\\s]*)?") String website,
            @NotBlank @Size(max=100) String fullName, @NotBlank @Email @Size(max=150) String email,
            @NotBlank @Size(max=100) String position, @NotBlank @Size(min=12,max=72) String password) {
        public Application {
            name = name == null ? null : name.strip();
            code = code == null ? null : code.strip();
            fullName = fullName == null ? null : fullName.strip();
            email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
            position = position == null ? null : position.strip();
            website = website == null || website.isBlank() ? null : website.strip();
        }
        @Override public String toString() { return "Application[redacted]"; }
    }
    public record Evidence(@NotBlank @Pattern(regexp="OFFICIAL_CALLBACK|OFFICIAL_EMAIL") String method,
            boolean institution, boolean representative, boolean mailbox) {
        public boolean complete() { return institution && representative && mailbox; }
    }
    public record Rejection(@NotBlank @Size(max=300) String reason) {}
    public record Details(long id, String name, String code, String website, String fullName,
            String email, String position, String status, OffsetDateTime expiresAt) {}
    public record Page(long count, List<Details> items) {}
    private static final String TABLE = "public.college_registration_requests";
    private static final String FIELDS = "id,name,code,website,full_name,email,position,status,expires_at";
    private static final String EFFECTIVE_STATUS =
            "CASE WHEN status='PENDING' AND expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE status END";
    private static final String REVIEW_ROWS = "(SELECT id,name,code,website,full_name,email,position," +
            EFFECTIVE_STATUS + " AS status,expires_at FROM " + TABLE + ") review";
    private static final RowMapper<Details> DETAILS = (r,i) -> new Details(r.getLong("id"),r.getString("name"),
        r.getString("code"),r.getString("website"),r.getString("full_name"),r.getString("email"),
        r.getString("position"),r.getString("status"),r.getObject("expires_at",OffsetDateTime.class));
    private final JdbcTemplate jdbc;
    private final PlatformAccess access;
    private final PlatformCollegeService colleges;
    private final PasswordEncoder encoder;
    public CollegeRegistrationService(JdbcTemplate jdbc, PlatformAccess access, PlatformCollegeService colleges,
            PasswordEncoder encoder) {
        this.jdbc=jdbc; this.access=access; this.colleges=colleges; this.encoder=encoder;
    }

    public void expire() {
        jdbc.update("UPDATE " + TABLE + " SET status='EXPIRED',password_hash=NULL,decided_at=expires_at " +
            "WHERE status='PENDING' AND expires_at<=clock_timestamp()");
    }

    public void submit(Application input) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw ApiError.INVALID_REQUEST.exception();
        String code=input.code().toUpperCase(Locale.ROOT);
        String email=input.email().strip().toLowerCase(Locale.ROOT);
        String hash=encoder.encode(input.password());
        expire();
        jdbc.update("INSERT INTO " + TABLE + " (name,code,website,full_name,email,position,password_hash) " +
            "SELECT ?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM public.colleges WHERE code=?) " +
            "AND NOT EXISTS (SELECT 1 FROM public.users WHERE lower(btrim(email))=?) ON CONFLICT DO NOTHING",
            input.name().strip(),code,input.website(),input.fullName().strip(),email,input.position().strip(),hash,code,email);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(Account actor,long after,String status) {
        access.verifySuperAdminForRead(actor);
        if (after<0 || !List.of("PENDING","REJECTED","APPROVED","EXPIRED").contains(status))
            throw ApiError.INVALID_REQUEST.exception();
        return new Page(jdbc.queryForObject("SELECT count(*) FROM "+REVIEW_ROWS+" WHERE status=?",Long.class,status),
            jdbc.query("SELECT "+FIELDS+" FROM "+REVIEW_ROWS+" WHERE status=? AND id>? ORDER BY id LIMIT 25",DETAILS,status,after));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Details detail(Account actor,long id) {
        access.verifySuperAdminForRead(actor);
        return find(id,false);
    }

    private Details find(long id,boolean lock) {
        return jdbc.query("SELECT "+FIELDS+" FROM "+(lock ? TABLE : REVIEW_ROWS)+" WHERE id=?"+(lock?" FOR UPDATE":""),DETAILS,id)
            .stream().findFirst().orElseThrow(ApiError.REGISTRATION_NOT_FOUND::exception);
    }

    private Details pending(long id) {
        Details row=find(id,true);
        if (row.status().equals("EXPIRED")) throw ApiError.REGISTRATION_EXPIRED.exception();
        if (!row.status().equals("PENDING")) throw ApiError.REGISTRATION_CONFLICT.exception();
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (!now.isBefore(row.expiresAt())) throw ApiError.REGISTRATION_EXPIRED.exception();
        return row;
    }

    public void approve(Account actor,long id,Evidence evidence) {
        long reviewer=access.requireCurrentSuperAdmin(actor);
        if (!evidence.complete()) throw ApiError.INVALID_REQUEST.exception();
        Details request=pending(id);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.users WHERE lower(btrim(email))=?)",
                Boolean.class,request.email()))) throw ApiError.REGISTRATION_CONFLICT.exception();
        var college=colleges.create(actor,new CollegeInput(request.code(),request.name()));
        colleges.status(actor,college.id(),true);
        var accounts=jdbc.queryForList("INSERT INTO public.users(full_name,email,password,college_id,is_active,is_verified,created_at,updated_at) "+
            "SELECT full_name,email,password_hash,?,true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM "+TABLE+
            " WHERE id=? ON CONFLICT DO NOTHING RETURNING id",Long.class,college.id(),id);
        if (accounts.isEmpty()) throw ApiError.REGISTRATION_CONFLICT.exception();
        long admin=accounts.getFirst();
        if (jdbc.update("INSERT INTO public.user_roles(user_id,role_id) SELECT ?,id FROM public.roles WHERE name='COLLEGE_ADMIN'",admin)!=1)
            throw new IllegalStateException("Required college administrator role missing");
        if (jdbc.update("UPDATE "+TABLE+" SET status='APPROVED',password_hash=NULL,reviewer_id=?,decided_at=statement_timestamp(),"+
            "verification_method=?,institution_verified=true,representative_verified=true,mailbox_verified=true,"+
            "verified_at=statement_timestamp(),college_id=?,admin_id=? WHERE id=? AND status='PENDING' AND expires_at>statement_timestamp()",
            reviewer,evidence.method(),college.id(),admin,id)!=1) throw ApiError.REGISTRATION_EXPIRED.exception();
        access.audit(reviewer,"COLLEGE_REGISTRATION_APPROVED",id);
    }

    public void reject(Account actor,long id,Rejection rejection) {
        long reviewer=access.requireCurrentSuperAdmin(actor);
        pending(id);
        if (jdbc.update("UPDATE "+TABLE+" SET status='REJECTED',password_hash=NULL,reviewer_id=?,decided_at=statement_timestamp(),"+
            "rejection_reason=? WHERE id=? AND status='PENDING' AND expires_at>statement_timestamp()",reviewer,rejection.reason().strip(),id)!=1)
            throw ApiError.REGISTRATION_EXPIRED.exception();
        access.audit(reviewer,"COLLEGE_REGISTRATION_REJECTED",id);
    }
}