package com.campussphere.backend.controller;

import java.io.IOException;
import java.util.List;
import com.campussphere.backend.security.AccountAccessPolicy.*;
import com.campussphere.backend.service.StudyMaterials;
import com.campussphere.backend.service.StudyMaterials.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/{area:student|faculty|college}/materials")
public class StudyMaterialController {
    private final StudyMaterials materials;
    public StudyMaterialController(StudyMaterials materials){this.materials=materials;}
    private CollegeRole role(String area){
        return switch(area){case "student" -> CollegeRole.STUDENT; case "faculty" -> CollegeRole.FACULTY; case "college" -> CollegeRole.COLLEGE_ADMIN; default -> throw new IllegalArgumentException();};
    }
    @GetMapping
    List<Item> list(@AuthenticationPrincipal Account actor,@PathVariable String area){return materials.list(actor,role(area));}
    @GetMapping("/{id}/file")
    ResponseEntity<byte[]> file(@AuthenticationPrincipal Account actor,@PathVariable String area,@PathVariable long id) throws IOException{
        var file=materials.download(actor,role(area),id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .header("Content-Disposition","attachment; filename=\"material-"+id+"."+file.format()+"\"")
            .contentType(file.format().equals("pdf")?MediaType.APPLICATION_PDF:MediaType.TEXT_PLAIN).body(file.bytes());
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    long upload(@AuthenticationPrincipal Account actor,@PathVariable String area,@RequestParam long assignmentId,
        @RequestParam String title,@RequestParam(defaultValue="") String description,@RequestParam String kind,
        @RequestParam MultipartFile file) throws IOException {
        actor.requireCollege(role(area),null);
        return materials.upload(actor,assignmentId,new Fields(title,description,kind),file);
    }
    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(@AuthenticationPrincipal Account actor,@PathVariable String area,@PathVariable long id,@RequestBody Fields fields){
        actor.requireCollege(role(area),null);materials.edit(actor,id,fields);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@AuthenticationPrincipal Account actor,@PathVariable String area,@PathVariable long id){materials.archive(actor,role(area),id);}
    @GetMapping("/{id}/events")
    List<Event> events(@AuthenticationPrincipal Account actor,@PathVariable String area,@PathVariable long id){
        actor.requireCollege(role(area),null);return materials.events(actor,id);
    }
}
