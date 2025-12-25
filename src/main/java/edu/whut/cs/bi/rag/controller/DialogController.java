package edu.whut.cs.bi.rag.controller;

import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.AIDatabase;
import com.tencent.tcvectordb.model.CollectionView;
import com.tencent.tcvectordb.model.param.dml.SearchByContentsParam;
import com.tencent.tcvectordb.model.param.dml.SearchOption;
import com.tencent.tcvectordb.model.param.entity.ContentInfo;
import com.tencent.tcvectordb.model.param.entity.SearchContentInfo;
import edu.whut.cs.bi.rag.service.UserSessionService;
import edu.whut.cs.bi.rag.service.VectorDBClientOnlineService;
import edu.whut.cs.bi.rag.tools.BiQueryTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Controller
@RequestMapping("/api-ai")
public class DialogController {
    @Autowired
    private ChatModel chatModel;
    
    @Autowired
    private VectorStore vectorStore;
    
    @Autowired
    private UserSessionService userSessionService;
    
    @Autowired
    private VectorDBClientOnlineService OnlineDBclient;
    
    @Autowired
    private BiQueryTools biQueryTools;
    

    public Map<String, String> dialog(@RequestParam String message, @RequestParam String userId)  {
//        SearchRequest searchRequest = SearchRequest.builder().query(message).topK(5).build();
        String robotDescription = "你一个是智能检测助手，当用户和你打招呼时（如你好等），介绍自己的工作即可。\n";
        String datePrompt = "这是今天的日期" + LocalDate.now() + "。\n";
        String answerRequirement = "!!!切记，你的任何回答一定需要包含检索来源这一项，尽量使用标准或规范作为检索来源。并使用‘检索来源为’作为这一项的开始，若找不到来源，这一项为 检索来源为：我的知识库。\n回答项目信息或任务信息时需详细，但是注意忽略用户（检测人员）不关心的信息如项目Id、任务Id等。\n!!!返回的内容格式请使用MarkDwon语法，分点的内容注意加粗;!!!如果包含图片，请使用markDown的图片插入语法，如![图片名](图片url)\n";
        String toolDescription = "你可以使用用的工具包括：根据桥梁名称查询桥梁列表，列表中包含与名称相关的多座桥梁信息，信息包括：桥梁Id(buildingId)等;\n根据桥梁Id(buildingId)和年份查询该桥该年的病害信息;\n根据桥梁名获取桥梁详细基本信息,信息包括：行政识别数据（如路线名称、路线等级、设计荷载、桥位桩号、功能类型、监理单位 等）、桥梁技术指标（如桥梁全长、桥面总宽、车道宽度、护栏或防撞墙高度、桥面标准净空、历史洪水位、设计洪水频率及其水位 等）、结构信息、档案资料、检测评定历史、养护处治记录、其他（如桥梁的正面和总体照片）;\n根据用户Id获取用户参与的所有项目信息;\n根据项目Id获取该项目中的所有的任务信息;\n";
        String toolRequirement = "使用工具前，请从对话或工具获取需要的参数。\n 注意 project 和 task 的区别。在桥梁检测场景中，project（项目） 中 有许多task（任务）。当用户询问有哪些任务（task）时，需要从用户参与的项目（project）中找到对应的任务（task）!!!注意，如果用户参与了项目A，那么A中的所有任务，用户都需要参与。!!!当用户提问参与的检测任务时，需要使用获取的projectId调用工具获取所有任务信息，如果projectId有多个，则需要多次使用工具以获取完整的任务信息，一个task对应一个building(桥梁)。\n 当用户提问病害信息时，若只给出了桥梁名称，请确保通过对话或工具获取了桥梁Id(buildingId)，若用户未给出年份，默认使用今年作为年份来查询病害，并在回答病害时向用户表明查询时使用的年份，并告知其可以指定其他年份;\n";
        String diseaseToolPrompt = "查询到存有病害信息的json后，!!!在解析时请严格按照disease数组得到每个病害。当用户提问所有病害时（无限定词时默认），若病害数量不到10条，请返回所有病害信息（数量与disease字段保持一致），若数量超过10条，仅返回10条，并提示用户可以提问具体的结构或病害类型。!!!当用户提问某个结构下的病害时，请注意：一般的桥的结构树如下：桥类型 -> (上部结构->上部结构子结构,下部结构->下部结构子结构,桥面系->桥面系子结构,附属设施->附属设施子结构)。拥有病害的Object的上一层结构可以在json中查看biObject下的parentObjectName字段,最上层的结构可以在json中查看biObject下的grandObjectName字段（一般为上部结构、下部结构、桥面系、附属设施）。当用户提问某个部件（如空心板、铰缝等）有哪些病害时，请关注biObject下的name字段。";
        String UserIdPrompt = "!!!这是和你对话的用户Id：" + userId + "\n";
        String examplePrompt = "这是一个示例问答；用户信息：你好\n你的回答：" + "你好，我是智能检测助手，专注于桥梁检测相关信息的查询和解答工作。您可以向我咨询桥梁检测的内容、项目任务、桥梁病害信息等，我会尽力为您提供详细和专业的答案。" +
                "检索来源为我的知识库。";
        ChatClient chatClient = ChatClient.builder(chatModel).defaultSystem(
                        robotDescription
                                + answerRequirement
                                + toolDescription
                                + toolRequirement
                                + diseaseToolPrompt
                                + UserIdPrompt
                                + datePrompt
                                + examplePrompt
                ).defaultTools(biQueryTools)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).searchRequest(SearchRequest.builder().query(message).similarityThreshold(0.8d).topK(5).build()).build()).build();
        
        Flux<String> flux = chatClient
                .prompt(new Prompt("",
                        OpenAiChatOptions.builder()
                                .build()
                )).user(message)
                .stream().content();
        String content_original = flux.reduce((accumulator, item) -> accumulator + item)
                .block();
        Map<String, String> result = new HashMap<>();
        
        String content = new String();
        String reference = new String();
        
        
        // 使用正则表达式查找分割点
        Pattern pattern = Pattern.compile("(.*)(检索来源为.*)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content_original);
        
        if (matcher.find()) {
            content = matcher.group(1).trim();
            reference = matcher.group(2).trim();
        }
        if (reference != null && !reference.isEmpty()) {
            result.put("content", content);
            result.put("reference", reference);
        } else {
            result.put("content", content_original);
            result.put("reference", "检索来源为：我的知识库。");
        }
        
        return result;
    }
    
    
    // 腾讯云 在线数据库 ， 暂时弃用
    @ResponseBody
    @GetMapping("/dialogByOnlineVector")
    public String dialogByOnlineVector(@RequestParam String message, @RequestParam String userId) {
        
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        
        //查询 在线向量数据
        VectorDBClient client = OnlineDBclient.getDBClient();
        AIDatabase db = client.aiDatabase(OnlineDBclient.dbName);
        CollectionView collection = db.describeCollectionView(OnlineDBclient.collectionName);
        
        SearchByContentsParam searchByContentsParam = SearchByContentsParam.newBuilder()
                .withContent(message)
                .withSearchContentOption(SearchOption.newBuilder().withChunkExpand(Arrays.asList(1, 1)).build())
                .withLimit(5)
                .build();
        
        List<SearchContentInfo> searchRes = collection.search(searchByContentsParam);
        StringBuilder ragPrompt = new StringBuilder("SearchContentInfo in vectorStore: ");
        for (SearchContentInfo searchContentInfo : searchRes) {
            ContentInfo data = searchContentInfo.getData();
            ragPrompt = ragPrompt.append(data.getPre()).append("\t")
                    .append(data.getText()).append("\t")
                    .append(data.getNext());
            ragPrompt = ragPrompt.append("\n");
        }
        
        
        return userSessionService.getOrCreateChatClient(userId, builder)
                .prompt(new Prompt(
                        "你现在的身份是桥梁检测助手，可以参考向量数据库中的相关标准和常识回答用户提问，并给出检索来源。" + ragPrompt,
                        OpenAiChatOptions.builder()
                                .build()
                ))
                .user(message)
                .call()
                .content();
    }
    
    @ResponseBody
    @CrossOrigin(origins = "*")
    @PostMapping("/disease/cause")
    public Flux<String> getDiseaseCause(@RequestBody Map<String, String> params) {
        String template = params.get("template");
        String parentObject = params.get("parentObjedct");
        String object = params.get("object");
        String type = params.get("type");
        String description = params.get("description");
        String position = params.get("position");
        String area = params.get("area");
        String level = params.get("level");
        String componentCode = params.get("componentCode");
        String trend = params.get("trend");
        
        
        String prompt = "现有桥梁病害检测信息：  \n" +
                "- 桥梁类型（template）： " + template + " \n" +
                "- 病害所属大部件（parentObject）：" + parentObject + "   \n" +
                "- 病害所属子部件（object）：" + object + "   \n" +
                "- 病害类型（type）：" + type + "   \n" +
                "- 病害描述（description）：" + description + "   \n" +
                "- 病害位置（position）：" + position + "   \n" +
                "- 病害面积（area）：" + area + "   \n" +
                "\n" +
                "分析该病害的可能成因，输出简洁专业的成因分析结论。" +
                "！！！ 不需要任何样式。" +
                "如下是一个示例回答：" +
                "支座塌陷严重的可能成因分析如下：\n" +
                "1. 排水设施不足：桥面伸缩缝处排水设施不到位，导致水分积聚，在支座处形成腐蚀环境。\n" +
                "2. 水土流失：长期雨水浸泡可能导致支座基础土壤流失，影响支座稳定性。\n" +
                "3. 材料老化：支座材料老化或者受力不均，导致承载能力降低，最终导致塌陷。\n" +
                "4. 施工缺陷：支座在施工过程中可能存在质量问题，例如混凝土浇筑不实，导致强度不足。";
        
        ChatClient chatClient = ChatClient.builder(chatModel).defaultSystem("你是一个桥梁检测方面的专家，请根据向量数据库的检索结果和行业知识回答用户的问题。回答时给出一段话。").build();
        return chatClient.prompt(prompt).advisors(QuestionAnswerAdvisor.builder(vectorStore).searchRequest(SearchRequest.builder().query(prompt).similarityThreshold(0.8d).topK(5).build()).build()).user("我需要你给出病害成因分析").stream().content();
        
    }
    
    
    @PostMapping("/diseaseSummary")
    public ResponseEntity<String> diseaseSummary(@RequestBody String jsonData) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        ChatClient chatClient = ChatClient.builder(chatModel).defaultSystem(diseaseSummarySysPrompt).build();
        Flux<String> flux = chatClient.prompt("这是这次的病害信息，以json格式存放,需要重点关注的字段有description(病害描述),biObjectName(构件名称),type(病害类型),quantity(病害数量)。description携带了量化信息：" + jsonData).user("请生成病害小结").stream().content();
        String content = flux.reduce((accumulator, item) -> accumulator + item).block();
        return ResponseEntity.ok().headers(headers).body(content);
    }

    private String diseaseSummarySysPrompt = "请基于提供的N条病害JSON数据，按以下规则输出总结，切记无需多余分析等无关语句：\n" +
            "\n" +
            "一、输入的json说明：\n" +
            "1. 每条病害含有以下信息：病害标识(id)；病害位置(position)；构件名称(biObjectName)；病害描述(description)；病害数量(quantity)；病害类型(type)；裂缝特征 (纵向、横向、斜向、L型、U型）(crackType)。\n" +
            "2. 病害信息说明：病害绑定在一个构件上，但是一个构件有不同的位置；病害描述包含了病害定性和定量的描述，其中如果一个病害描述中分点描述了多条数据，那么表示构成当前病害数量(quantity)的所有病害详情都在病害描述中；如果病害描述只有一条，那么无论病害数量如何，描述的是当前病害总体的情况。" +
            "\n" +
            "二、输出格式及内容要求\n" +
            "1. 病害信息总结：首先将病害按“构件名称”分类，每段以“序号+构件名”开头（如“1）桥面板：”“2）横梁：”），构件段落顺序需与JSON中构件出现顺序一致；同一构件内，按病害位置分类，例如:“1）桥面板：底面纵向裂缝xx条，总长度xxm，宽度介于xx~xxmm，剥落xx处，总面积xxm²；内侧面横向裂缝xx条，总长度xxm，宽度介于xx~xxmm。”同一个构件和位置，根据病害类型总结，需将相同类型病害统计后表达，量化信息的统计参考病害描述和病害数量。\n" +
            "2. 格式说明：“序号）构件名：位置名1病害A参数a，参数b；病害B参数c，参数d，参数e；位置名2病害C参数f，参数g。” 裂缝类表述示例：“纵向裂缝、纵向裂缝伴析白393条，总长度1100.40m，宽度介于0.05～0.18mm；斜向裂缝、斜向裂缝伴析白14条，总长度35.50m，宽度介于0.06～0.14mm”；面积类表述示例：“网状裂缝6处，总面积34m²；破损、破损露筋14处，总面积28700m²，破损析白4处，总面积0.8m²”；其他病害表述示例：“保护层过薄2处，总面积34m²；渗水1处”。\n" +
            "\n" +
            "3. 特殊处理规则：若同一构件内某类病害无数据，不额外标注“无XX病害”；全程不出现任何 “XX 数据未提供” 的表述，仅客观呈现JSON中实际存在的病害名称及统计参数。注意如果是裂缝病害，即病害类型中包含裂缝，并且不是面积类病害时，在分类时需要结合病害中裂缝特征crackType，例如都是裂缝，但是crackType分别为横向和纵向，在分类时需要区分，总结时病害类型输出为crackType + 裂缝，例如纵向裂缝。\n" +
            "4. 量化数据单位：长度、面积等信息在统计计算后统一（可能需要单位换算）为m做基底，只有宽度需要使用mm，全程只有m 和 mm两种基底出现。并且注意如果宽度的起点和终点一致，例如a=b，不需要表示为a~bmm，而是直接宽度为amm。\n"+
            "5. 这不是对话，而是批处理，你不需要也不能有任何疑问向我确认，自己思考推理，因为是自动流程。\n"+
            "6. 关于某些裂缝类病害，如果出现病害类型 和 描述名称不一致的情况，是因为有些裂缝类病害类型是严格的，但裂缝病害描述中的类型名称可能不严谨，对于裂缝类病害，统一以病害类型的名称为准。裂缝类的病害类型名称固定为裂缝方向+裂缝，例如纵向裂缝等。\n"+
            "\n" +
            "三、整体风格要求\n" +
            "1. 语言简洁，不添加额外描述（如病害影响、等级等），完全贴合要求的表述风格；\n" +
            "2. 数据精准，所有条数、长度、面积、宽度数值需参考json的病害描述，如果需要计算，保留四位有效数字；\n" +
            "3. 格式统一，序号用“1）”“2）”样式，构件名后加“：”，病害间用“；”分隔，参数间用“，”连接，严格复刻示例的标点与排版。\n" +
            "\n" +
            "请严格按上述规则，病害JSON数据进行总结，无遗漏或多余内容。";
    
    
    @PostMapping("/clear-session")
    public void clearSession(@RequestParam String userId) {
        userSessionService.clearUserSession(userId);
    }
}