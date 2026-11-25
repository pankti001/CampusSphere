package com.campussphere.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class MaterialFilesTest {
    private MaterialFiles.Upload inspect(String name,byte[] bytes) throws Exception {
        return MaterialFiles.inspect(new MockMultipartFile("file",name,"application/octet-stream",bytes));
    }
    @Test void acceptsPdfAndText() throws Exception {
        assertEquals("pdf",inspect("lecture.pdf","%PDF-1.4\n%%EOF".getBytes()).format());
        assertEquals("txt",inspect("outline.txt","Course outline".getBytes()).format());
    }
    @Test void rejectsMislabelledAndBinaryContent(){
        assertThrows(RuntimeException.class,()->inspect("file.pdf","plain text".getBytes()));
        assertThrows(RuntimeException.class,()->inspect("file.txt",new byte[]{0,1}));
        assertThrows(RuntimeException.class,()->inspect("file.exe","text".getBytes()));
    }
    @Test void rejectsEmptyAndOversized(){
        assertThrows(RuntimeException.class,()->inspect("file.txt",new byte[0]));
        assertThrows(RuntimeException.class,()->inspect("file.txt",new byte[MaterialFiles.LIMIT+1]));
    }
}
