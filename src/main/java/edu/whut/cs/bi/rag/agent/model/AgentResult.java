package edu.whut.cs.bi.rag.agent.model;

import lombok.Data;

import java.util.List;

@Data
public class AgentResult {
    
    private String message;
    
    private List<String> attachments;
    
    private List<String> references;
    
}
