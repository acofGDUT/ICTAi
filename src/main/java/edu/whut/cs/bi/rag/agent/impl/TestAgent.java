package edu.whut.cs.bi.rag.agent.impl;

import edu.whut.cs.bi.rag.agent.core.BaseAgent;
import edu.whut.cs.bi.rag.agent.model.AgentResult;
import org.springframework.stereotype.Component;

@Component
public class TestAgent implements BaseAgent {
    @Override
    public AgentResult run(String prompt) {
        return new AgentResult();
    }
    
    @Override
    public String getDescription() {
        return "测试agent";
    }
    
    @Override
    public String getName() {
        return "testAgent";
    }
}
