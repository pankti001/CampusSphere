package com.campussphere.backend.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;
import com.campussphere.backend.security.AccountAccessPolicy.*;
import com.campussphere.backend.security.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly=true)
public class StudyMaterials {
    public record Fields(String title,String description,String kind) {
        void validate() {
            if(title==null || title.isBlank() || title.length()>160 || description==null || description.length()>2000 ||
               kind==null || !Set.of("NOTES","READING","REFERENCE").contains(kind)) throw ApiError.INVALID_REQUEST.exception();
        }
    }
    public record Item(long id,String title,String description,String kind,String format,String subject,String division,
        String faculty,String semester,String year,String uploaded,boolean archived) {}
    public record Download(byte[] bytes,String format) {}
    public record Event(String action,String actor,String time) {}
    private final JdbcTemplate db;
    private final MaterialFiles files;
    public StudyMaterials(JdbcTemplate db, MaterialFiles files){this.db=db;this.files=files;}
    private static final String JOIN = """
        FROM public.study_materials m JOIN public.faculty_subject_assignments a ON (a.college_id,a.id)=(m.college_id,m.assignment_id)
        JOIN public.academic_subjects s ON s.id=a.subject_id JOIN public.academic_divisions d ON d.id=a.division_id
        JOIN public.users u ON u.id=a.faculty_id JOIN public.academic_semesters sem ON sem.id=a.semester_id
        JOIN public.academic_years y ON y.id=a.academic_year_id WHERE m.college_id=?
        """;
    private static String scope(CollegeRole role){
        return switch(role){
            case COLLEGE_ADMIN -> " AND ?>0 ";
            case FACULTY -> " AND a.faculty_id=? AND a.ended_at IS NULL AND m.archived_at IS NULL ";
            case STUDENT -> """
                AND m.archived_at IS NULL AND EXISTS(SELECT 1 FROM public.student_enrollments e
                WHERE e.student_id=? AND e.ended_at IS NULL AND (e.college_id,e.department_id,e.program_id,e.academic_year_id,e.semester_id,e.division_id)
                =(a.college_id,a.department_id,a.program_id,a.academic_year_id,a.semester_id,a.division_id))
                """;
        };
    }
    public List<Item> list(Account actor,CollegeRole role){
        return db.query("SELECT m.*,s.name subject,d.name division,u.full_name faculty,sem.name semester,y.code year "+JOIN+scope(role)+" ORDER BY m.id DESC",
            (r,i)->new Item(r.getLong("id"),r.getString("title"),r.getString("description"),r.getString("kind"),r.getString("format"),
            r.getString("subject"),r.getString("division"),r.getString("faculty"),r.getString("semester"),r.getString("year"),
            r.getString("created_at"),r.getObject("archived_at")!=null),actor.requireCollege(role,null),actor.id());
    }
    private UUID key(Account actor,CollegeRole role,long id,boolean lock){
        return db.query("SELECT m.storage_key "+JOIN+scope(role)+" AND m.id=? AND m.archived_at IS NULL "+(lock?" FOR UPDATE OF m FOR SHARE OF a":""),
            (r,i)->r.getObject(1,UUID.class),actor.requireCollege(role,null),actor.id(),id).stream().findFirst().orElseThrow(ApiError.MATERIAL_NOT_FOUND::exception);
    }
    public Download download(Account actor,CollegeRole role,long id) throws IOException{
        UUID key=key(actor,role,id,false);
        String format=db.queryForObject("SELECT format FROM public.study_materials WHERE storage_key=?",String.class,key);
        return new Download(Files.readAllBytes(files.path(key)),format);
    }
    @Transactional(rollbackFor=IOException.class)
    public long upload(Account actor,long assignment,Fields fields,MultipartFile file) throws IOException{
        long college=actor.requireCollege(CollegeRole.FACULTY,null);fields.validate();
        if(db.queryForList("SELECT id FROM public.faculty_subject_assignments WHERE college_id=? AND id=? AND faculty_id=? AND ended_at IS NULL FOR SHARE",
            Long.class,college,assignment,actor.id()).isEmpty())throw ApiError.MATERIAL_NOT_FOUND.exception();
        var upload=MaterialFiles.inspect(file);UUID key=UUID.randomUUID();
        long id=db.queryForObject("INSERT INTO public.study_materials(college_id,assignment_id,title,description,kind,format,storage_key,byte_size) VALUES(?,?,?,?,?,?,?,?) RETURNING id",
            Long.class,college,assignment,fields.title().strip(),fields.description().strip(),fields.kind(),upload.format(),key,upload.bytes().length);
        event(college,id,actor.id(),"UPLOAD");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK)try{Files.deleteIfExists(files.path(key));}catch(IOException e){throw new UncheckedIOException(e);}}
        });
        files.save(key,upload.bytes());return id;
    }
    private void event(long college,long id,long actor,String action){
        db.update("INSERT INTO public.study_material_events(college_id,material_id,actor_id,action) VALUES(?,?,?,?)",college,id,actor,action);
    }
    @Transactional
    public void edit(Account actor,long id,Fields fields){
        fields.validate();key(actor,CollegeRole.FACULTY,id,true);
        db.update("UPDATE public.study_materials SET title=?,description=?,kind=? WHERE id=?",fields.title().strip(),fields.description().strip(),fields.kind(),id);
        event(actor.requireCollege(CollegeRole.FACULTY,null),id,actor.id(),"EDIT");
    }
    @Transactional
    public void archive(Account actor,CollegeRole role,long id){
        if(role==CollegeRole.STUDENT)throw ApiError.ACCESS_DENIED.exception();
        key(actor,role,id,true);db.update("UPDATE public.study_materials SET archived_at=statement_timestamp() WHERE id=?",id);
        event(actor.requireCollege(role,null),id,actor.id(),"ARCHIVE");
    }
    public List<Event> events(Account actor,long id){
        return db.query("SELECT e.action,u.full_name,e.occurred_at FROM public.study_material_events e JOIN public.users u ON u.id=e.actor_id WHERE e.college_id=? AND e.material_id=? ORDER BY e.id",
            (r,i)->new Event(r.getString(1),r.getString(2),r.getString(3)),actor.requireCollege(CollegeRole.COLLEGE_ADMIN,null),id);
    }
}
