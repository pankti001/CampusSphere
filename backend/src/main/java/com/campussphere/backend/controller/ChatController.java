package com.campussphere.backend.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.StudentChats;
import com.campussphere.backend.service.StudentChats.*;
import com.campussphere.backend.service.StudyMaterials.FileDownload;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Student profile, groups and chat. Everything is under /api/student/**, which Spring Security limits to the
 * STUDENT role; there are no faculty, College Admin or platform routes into chats.
 */
@RestController
public class ChatController {
    private static final String CHATS = "/api/student/chats";
    private static final String ID = "/{id:\\d+}";
    private static final String MESSAGE = ID + "/messages/{messageId:\\d+}";

    private final StudentChats chats;

    public ChatController(StudentChats chats) {
        this.chats = chats;
    }

    public record Count(long count) {}

    @GetMapping("/api/student/me")
    Me me(@AuthenticationPrincipal Account actor) {
        return chats.me(actor);
    }

    @GetMapping(path = CHATS + "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal Account actor) {
        return chats.connect(actor);
    }

    @GetMapping(CHATS)
    List<Conversation> conversations(@AuthenticationPrincipal Account actor) {
        return chats.conversations(actor);
    }

    @GetMapping(CHATS + "/unread")
    Count unread(@AuthenticationPrincipal Account actor) {
        return new Count(chats.unread(actor));
    }

    @GetMapping(CHATS + ID)
    Conversation conversation(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return chats.conversation(actor, id);
    }

    @GetMapping(CHATS + "/people")
    List<Person> people(@AuthenticationPrincipal Account actor, @RequestParam(defaultValue = "") String q) {
        return chats.people(actor, q);
    }

    @PostMapping(path = CHATS + "/direct", consumes = MediaType.APPLICATION_JSON_VALUE)
    Conversation direct(@AuthenticationPrincipal Account actor, @RequestBody StudentRef ref) {
        return chats.direct(actor, ref);
    }

    @GetMapping(CHATS + "/groups/open")
    List<OpenGroup> openGroups(@AuthenticationPrincipal Account actor, @RequestParam(defaultValue = "") String q) {
        return chats.openGroups(actor, q);
    }

    @PostMapping(path = CHATS + "/groups", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Conversation createGroup(@AuthenticationPrincipal Account actor, @RequestBody GroupInput input) {
        return chats.createGroup(actor, input);
    }

    @PutMapping(path = CHATS + ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    Conversation updateGroup(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody GroupInput input) {
        return chats.updateGroup(actor, id, input);
    }

    @PostMapping(CHATS + ID + "/archive")
    Conversation archive(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return chats.archiveGroup(actor, id);
    }

    @PostMapping(CHATS + ID + "/join")
    Conversation join(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return chats.join(actor, id);
    }

    @PostMapping(CHATS + ID + "/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        chats.leave(actor, id);
    }

    /** Deletes the chat or group from the signed-in student's own list and history only. */
    @PostMapping(CHATS + ID + "/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteForMe(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        chats.deleteForMe(actor, id);
    }

    /** The owner deletes their study group for everyone. */
    @PostMapping(CHATS + ID + "/delete-for-everyone")
    Conversation deleteForEveryone(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return chats.deleteForEveryone(actor, id);
    }

    @PostMapping(CHATS + "/class/rejoin")
    Conversation rejoinClass(@AuthenticationPrincipal Account actor) {
        return chats.rejoinClass(actor);
    }

    @GetMapping(CHATS + ID + "/members")
    List<Member> members(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return chats.members(actor, id);
    }

    @PostMapping(path = CHATS + ID + "/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    List<Member> addMember(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody StudentRef ref) {
        return chats.addMember(actor, id, ref);
    }

    @DeleteMapping(CHATS + ID + "/members/{studentId:\\d+}")
    List<Member> removeMember(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long studentId) {
        return chats.removeMember(actor, id, studentId);
    }

    @GetMapping(CHATS + ID + "/messages")
    List<Message> messages(@AuthenticationPrincipal Account actor, @PathVariable long id,
                           @RequestParam(required = false) Long before) {
        return chats.messages(actor, id, before);
    }

    @PostMapping(path = CHATS + ID + "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Message send(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody MessageInput input) {
        return chats.send(actor, id, input);
    }

    @PostMapping(path = CHATS + ID + "/messages/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Message sendFile(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestParam("file") MultipartFile file,
                     @RequestParam(required = false) String body, @RequestParam UUID clientKey) throws IOException {
        return chats.sendFile(actor, id, file, body, clientKey);
    }

    @DeleteMapping(CHATS + MESSAGE)
    Message delete(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long messageId) {
        return chats.delete(actor, id, messageId);
    }

    @PostMapping(path = CHATS + ID + "/read", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody ReadInput input) {
        chats.read(actor, id, input);
    }

    @GetMapping(CHATS + MESSAGE + "/file")
    ResponseEntity<Resource> file(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long messageId) {
        FileDownload download = chats.file(actor, id, messageId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.sizeBytes())
                .body(new FileSystemResource(download.path()));
    }

    @PostMapping(path = CHATS + MESSAGE + "/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long messageId,
                @RequestBody ReportInput input) {
        chats.report(actor, id, messageId, input);
    }

    @GetMapping(CHATS + "/blocks")
    List<Person> blocked(@AuthenticationPrincipal Account actor) {
        return chats.blocked(actor);
    }

    @PostMapping(path = CHATS + "/blocks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(@AuthenticationPrincipal Account actor, @RequestBody StudentRef ref) {
        chats.block(actor, ref);
    }

    @DeleteMapping(CHATS + "/blocks/{studentId:\\d+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unblock(@AuthenticationPrincipal Account actor, @PathVariable long studentId) {
        chats.unblock(actor, studentId);
    }
}
