package edu.whut.cs.bi.rag.agent.impl;

import edu.whut.cs.bi.rag.agent.core.BaseAgent;
import edu.whut.cs.bi.rag.agent.model.AgentResult;
import edu.whut.cs.bi.rag.tools.BiQueryTools;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class DialogAgent implements BaseAgent {
    
    @Resource
    BiQueryTools biQueryTools;
    
    private final ChatClient chatClient;
    
    @Resource
    private VectorStore vectorStore;
    
    public DialogAgent(ChatClient.Builder builder) {
        chatClient = builder.build();
    }
    
    String robotDescription = "你一个是智能检测助手，当用户和你打招呼时（如你好等），介绍自己的工作即可。\n";
    String datePrompt = "这是今天的日期" + LocalDate.now() + "。\n";
    String answerRequirement = "!!!切记，你的任何回答一定需要包含检索来源这一项，尽量使用标准或规范作为检索来源。并使用‘检索来源为’作为这一项的开始，若找不到来源，这一项为 检索来源为：我的知识库。\n回答项目信息或任务信息时需详细，但是注意忽略用户（检测人员）不关心的信息如项目Id、任务Id等。\n!!!返回的内容格式请使用MarkDwon语法，分点的内容注意加粗;!!!如果包含图片，请使用markDown的图片插入语法，如![图片名](图片url)\n";
    String toolDescription = "你可以使用用的工具包括：根据桥梁名称查询桥梁列表，列表中包含与名称相关的多座桥梁信息，信息包括：桥梁Id(buildingId)等;\n根据桥梁Id(buildingId)和年份查询该桥该年的病害信息;\n根据桥梁名获取桥梁详细基本信息,信息包括：行政识别数据（如路线名称、路线等级、设计荷载、桥位桩号、功能类型、监理单位 等）、桥梁技术指标（如桥梁全长、桥面总宽、车道宽度、护栏或防撞墙高度、桥面标准净空、历史洪水位、设计洪水频率及其水位 等）、结构信息、档案资料、检测评定历史、养护处治记录、其他（如桥梁的正面和总体照片）;\n根据用户Id获取用户参与的所有项目信息;\n根据项目Id获取该项目中的所有的任务信息;\n";
    String toolRequirement = "使用工具前，请从对话或工具获取需要的参数。\n 注意 project 和 task 的区别。在桥梁检测场景中，project（项目） 中 有许多task（任务）。当用户询问有哪些任务（task）时，需要从用户参与的项目（project）中找到对应的任务（task）!!!注意，如果用户参与了项目A，那么A中的所有任务，用户都需要参与。!!!当用户提问参与的检测任务时，需要使用获取的projectId调用工具获取所有任务信息，如果projectId有多个，则需要多次使用工具以获取完整的任务信息，一个task对应一个building(桥梁)。\n 当用户提问病害信息时，若只给出了桥梁名称，请确保通过对话或工具获取了桥梁Id(buildingId)，若用户未给出年份，默认使用今年作为年份来查询病害，并在回答病害时向用户表明查询时使用的年份，并告知其可以指定其他年份;\n";
    String diseaseToolPrompt = "查询到存有病害信息的json后，!!!在解析时请严格按照disease数组得到每个病害。当用户提问所有病害时（无限定词时默认），若病害数量不到10条，请返回所有病害信息（数量与disease字段保持一致），若数量超过10条，仅返回10条，并提示用户可以提问具体的结构或病害类型。!!!当用户提问某个结构下的病害时，请注意：一般的桥的结构树如下：桥类型 -> (上部结构->上部结构子结构,下部结构->下部结构子结构,桥面系->桥面系子结构,附属设施->附属设施子结构)。拥有病害的Object的上一层结构可以在json中查看biObject下的parentObjectName字段,最上层的结构可以在json中查看biObject下的grandObjectName字段（一般为上部结构、下部结构、桥面系、附属设施）。当用户提问某个部件（如空心板、铰缝等）有哪些病害时，请关注biObject下的name字段。";
    String examplePrompt = "这是一个示例问答；用户信息：你好\n你的回答：" + "你好，我是智能检测助手，专注于桥梁检测相关信息的查询和解答工作。您可以向我咨询桥梁检测的内容、项目任务、桥梁病害信息等，我会尽力为您提供详细和专业的答案。" +
            "检索来源为我的知识库。";
    
    private String SYSTEM_PROMPT =
            robotDescription +
                    datePrompt +
                    answerRequirement +
                    toolDescription +
                    toolRequirement +
                    diseaseToolPrompt +
                    examplePrompt +
                    "如果查询的是病害，并且病害数量少于10个，则在message中详细列出每一条病害";
    
    
    private static final String DESCRIPTION =
            """
            作用：可以查询向量数据库和桥梁数据库，回答用户提问。
            一定需要提供userId。
            """;
    
    
    @Override
    public AgentResult run(String prompt) {
        AgentResult result = chatClient
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .tools(biQueryTools)
                .advisors(new QuestionAnswerAdvisor(vectorStore))
                .call()
                .entity(AgentResult.class);
        
        
        return result;
    }
    
    @Override
    public String getDescription() {
        return DESCRIPTION;
    }
    
    @Override
    public String getName() {
        return "dialogAgent";
    }
}
