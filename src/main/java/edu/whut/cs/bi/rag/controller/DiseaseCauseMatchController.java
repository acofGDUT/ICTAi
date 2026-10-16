package edu.whut.cs.bi.rag.controller;

import edu.whut.cs.bi.rag.service.DiseaseCauseMatchService;
import edu.whut.cs.bi.rag.service.ResponsesApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api-ai")
public class DiseaseCauseMatchController {
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    private static final MediaType TEXT_UTF8 = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final DiseaseCauseMatchService diseaseCauseMatchService;

    public DiseaseCauseMatchController(DiseaseCauseMatchService diseaseCauseMatchService) {
        this.diseaseCauseMatchService = diseaseCauseMatchService;
    }

    @PostMapping("/disease/cause-match")
    public ResponseEntity<String> match(@RequestBody String jsonData) {
        try {
            return ResponseEntity.ok().contentType(JSON_UTF8).body(diseaseCauseMatchService.match(jsonData));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().contentType(TEXT_UTF8).body(exception.getMessage());
        } catch (ResponsesApiException exception) {
            return ResponseEntity.status(502).contentType(TEXT_UTF8).body(exception.getMessage());
        }
    }
}
