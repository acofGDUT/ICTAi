package edu.whut.cs.bi.rag.agent.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent自动注册器
 * 自动发现所有实现BaseAgent接口的Bean
 */
@Component
public class AgentRegistry {
    
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 存储所有注册的Agent
     */
    private final Map<String, BaseAgent> registeredAgents = new ConcurrentHashMap<>();
    
    /**
     * 应用启动后自动注册所有Agent
     */
    @PostConstruct
    public void registerAllAgents() {
        // 获取所有实现BaseAgent接口的Bean
        Map<String, BaseAgent> agentBeans = applicationContext.getBeansOfType(BaseAgent.class);
        for (Map.Entry<String, BaseAgent> entry : agentBeans.entrySet()) {
            BaseAgent agent = entry.getValue();
            registerAgent(agent);
        }
    }
    
    /**
     * 注册单个Agent
     */
    private void registerAgent(BaseAgent agent) {
        String agentName = agent.getName();
        registeredAgents.put(agentName, agent);
    }

    /**
     * 获取所有已注册的Agent信息
     */
    public Map<String, BaseAgent> getAllAgents() {
        return new ConcurrentHashMap<>(registeredAgents);
    }
    
    /**
     * 根据名称获取Agent
     */
    public BaseAgent getAgent(String agentName) {
        return registeredAgents.getOrDefault(agentName, null);
    }

}
