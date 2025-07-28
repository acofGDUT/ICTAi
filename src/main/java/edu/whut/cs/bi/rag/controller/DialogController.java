package edu.whut.cs.bi.rag.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.gson.JsonObject;
import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.AIDatabase;
import com.tencent.tcvectordb.model.CollectionView;
import com.tencent.tcvectordb.model.Document;
import com.tencent.tcvectordb.model.param.dml.SearchByContentsParam;
import com.tencent.tcvectordb.model.param.dml.SearchOption;
import com.tencent.tcvectordb.model.param.entity.ContentInfo;
import com.tencent.tcvectordb.model.param.entity.SearchContentInfo;
import edu.whut.cs.bi.rag.service.UserSessionService;
import edu.whut.cs.bi.rag.service.VectorDBClientOnlineService;
import edu.whut.cs.bi.rag.tools.BiQueryTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
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
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
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
    private VectorDBClientOnlineService  OnlineDBclient;

    @Autowired
    private BiQueryTools biQueryTools;
    @ResponseBody
    @CrossOrigin(origins = "*")
    @GetMapping("/dialog")
    public Map<String, String> dialog(@RequestParam String message, @RequestParam String userId) throws JsonProcessingException {
//        SearchRequest searchRequest = SearchRequest.builder().query(message).topK(5).build();
        String robotDescription = "你一个是智能检测助手，当用户和你打招呼时（如你好等），介绍自己的工作即可。\n";
        String datePrompt = "这是今天的日期" + LocalDate.now() + "。\n";
        String answerRequirement = "!!!切记，你的任何回答一定需要包含检索来源这一项，尽量使用标准或规范作为检索来源。并使用‘检索来源为’作为这一项的开始，若找不到来源，这一项为 检索来源为：我的知识库。\n回答项目信息或任务信息时需详细，但是注意忽略用户（检测人员）不关心的信息如项目Id、任务Id等。\n!!!返回的内容格式请使用MarkDwon语法，分点的内容注意加粗;!!!如果包含图片，请使用markDown的图片插入语法，如![图片名](图片url)\n";
        String toolDescription =  "你可以使用用的工具包括：根据桥梁名称查询桥梁列表，列表中包含与名称相关的多座桥梁信息，信息包括：桥梁Id(buildingId)等;\n根据桥梁Id(buildingId)和年份查询该桥该年的病害信息;\n根据桥梁名获取桥梁详细基本信息,信息包括：行政识别数据（如路线名称、路线等级、设计荷载、桥位桩号、功能类型、监理单位 等）、桥梁技术指标（如桥梁全长、桥面总宽、车道宽度、护栏或防撞墙高度、桥面标准净空、历史洪水位、设计洪水频率及其水位 等）、结构信息、档案资料、检测评定历史、养护处治记录、其他（如桥梁的正面和总体照片）;\n根据用户Id获取用户参与的所有项目信息;\n根据项目Id获取该项目中的所有的任务信息;\n";
        String toolRequirement = "使用工具前，请从对话或工具获取需要的参数。\n 注意 project 和 task 的区别。在桥梁检测场景中，project（项目） 中 有许多task（任务）。当用户询问有哪些任务（task）时，需要从用户参与的项目（project）中找到对应的任务（task）!!!注意，如果用户参与了项目A，那么A中的所有任务，用户都需要参与。!!!当用户提问参与的检测任务时，需要使用获取的projectId调用工具获取所有任务信息，如果projectId有多个，则需要多次使用工具以获取完整的任务信息，一个task对应一个building(桥梁)。\n 当用户提问病害信息时，若只给出了桥梁名称，请确保通过对话或工具获取了桥梁Id(buildingId)，若用户未给出年份，默认使用今年作为年份来查询病害，并在回答病害时向用户表明查询时使用的年份，并告知其可以指定其他年份;\n";
        String diseaseToolPrompt = "查询到存有病害信息的json后，!!!在解析时请严格按照disease数组得到每个病害。当用户提问所有病害时（无限定词时默认），若病害数量不到10条，请返回所有病害信息（数量与disease字段保持一致），若数量超过10条，仅返回10条，并提示用户可以提问具体的结构或病害类型。!!!当用户提问某个结构下的病害时，请注意：一般的桥的结构树如下：桥类型 -> (上部结构->上部结构子结构,下部结构->下部结构子结构,桥面系->桥面系子结构,附属设施->附属设施子结构)。拥有病害的Object的上一层结构可以在json中查看biObject下的parentObjectName字段,最上层的结构可以在json中查看biObject下的grandObjectName字段（一般为上部结构、下部结构、桥面系、附属设施）。当用户提问某个部件（如空心板、铰缝等）有哪些病害时，请关注biObject下的name字段。";
        String UserIdPrompt = "!!!这是和你对话的用户Id："+userId+"\n";
        String examplePrompt =  "这是一个示例问答；用户信息：你好\n你的回答：" +"你好，我是智能检测助手，专注于桥梁检测相关信息的查询和解答工作。您可以向我咨询桥梁检测的内容、项目任务、桥梁病害信息等，我会尽力为您提供详细和专业的答案。"+
                                        "检索来源为我的知识库。";
        ChatClient.Builder builder = ChatClient.builder(chatModel).defaultSystem(
                    robotDescription
                        + answerRequirement
                        + toolDescription
                        + toolRequirement
                        + diseaseToolPrompt
                        + UserIdPrompt
                        + datePrompt
                        + examplePrompt
                ).defaultTools(biQueryTools)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).searchRequest(SearchRequest.builder().query(message).similarityThreshold(0.8d).topK(5).build()).build());

        Flux<String> flux = userSessionService.getOrCreateChatClient(userId, builder)
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
        if(reference != null && !reference.isEmpty()){
            result.put("content", content);
            result.put("reference", reference);
        }else{
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
        VectorDBClient  client = OnlineDBclient.getDBClient();
        AIDatabase db = client.aiDatabase(OnlineDBclient.dbName);
        CollectionView collection = db.describeCollectionView(OnlineDBclient.collectionName);

        SearchByContentsParam searchByContentsParam = SearchByContentsParam.newBuilder()
                .withContent(message)
                .withSearchContentOption(SearchOption.newBuilder().withChunkExpand(Arrays.asList(1,1)).build())
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
                        "你现在的身份是桥梁检测助手，可以参考向量数据库中的相关标准和常识回答用户提问，并给出检索来源。"+ragPrompt,
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
    public Flux<String> getDiseaseCause(@RequestBody Map<String,String> params) {
         String template = params.get("template");
         String parentObject = params.get("parentObjedct");
         String object = params.get("object");
         String type =  params.get("type");
         String description = params.get("description");
         String position =  params.get("position");
         String area =  params.get("area");
         String level =  params.get("level");
         String componentCode =  params.get("componentCode");
         String trend =  params.get("trend");


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
        ChatClient chatClient = ChatClient.builder(chatModel).defaultSystem("你是一个桥梁检测报告书写人员，负责统计病害的类型和个数，!!!请仔细计算，确保数量和量化信息准确!!!如果病害存在面积等量化信息，需要进行统计，若没有则统计数量即可。生成一段病害小结，小结的格式请严格参照示例，直接列出病害总结，以便于插入文档中。无需给出个人观点和建议。\n"+"这是两个示例回答，示例一：\n" +
                "1）桥面板：纵向裂缝、纵向裂缝伴析白393条，总长度110040cm，宽度介于0.05～0.18mm；斜向裂缝、斜向裂缝伴析白14条，总长度3550cm，宽度介于0.06～0.14mm；网状裂缝6处，总面积340000cm²；破损、破损露筋14处，总面积28700m²，破损析白4处，总面积8000cm²；锈胀露筋5处，总面积4200cm²；空洞露筋1处，面积200cm²；麻面、麻面露筋7处，总面积39300cm²；保护层过薄2处，总面积340000cm²；渗水1处。\n" +
                "2）横梁：竖向裂缝、竖向裂缝伴析白176条，总长度31025cm，宽度介于0.05～0.22mm；横向裂缝35条，总长度12550cm，宽度介于0.06～0.15mm；斜向裂缝19条，总长度5740cm，宽度介于0.06～0.14mm；L型裂缝4条，总长度320cm，宽度0.10mm；破损、破损露筋9处，总面积34500cm²；网状裂缝16处，总面积215600cm²，宽度介于0.06～0.22mm；麻面、麻面露筋2处，面积4600cm²；锈胀露筋1处，面积1000cm²；空洞露筋1处，面积600cm²；析白1处，面积2000cm²。\n" +
                "3）纵梁：纵向裂缝、纵向裂缝伴析白13条，长度4190cm，宽度介于0.08～0.15mm；竖向裂缝1条，长度100cm，宽度0.05mm；网状裂缝3处，总面积33000cm²，宽度0.12mm；锈胀露筋25处，总面积37600cm²；破损、破损露筋14处，总面积30250cm²；麻面露筋3处，总面积5300cm²；析白1处，面积600cm²；保护层过薄2处，总面积6000cm²。\n" +
                "4）齿块：竖向裂缝3条，总长度250cm，宽度0.08mm；锈胀露筋8处，总面积37300cm²；破损露筋2处，总面积1800cm²。\n"
                +"示例二："+
                "斜拉索划痕88处，总面积429cm²；刮痕40处，总面积690cm²；压痕6处，总面积48cm²。").build();
        Flux<String> flux  = chatClient.prompt("这是这次的病害信息，以json格式存放,需要重点关注的字段有description(病害描述),biObjectName(存在病害的桥梁结构信息),type(病害类型),quantity(病害数量)。description携带了面积等量化信息："+jsonData).user("请生成病害小结").stream().content();
        String content = flux.reduce((accumulator, item) -> accumulator + item).block();
        return ResponseEntity.ok().headers(headers).body(content);
    }



    @PostMapping("/clear-session")
    public void clearSession(@RequestParam String userId) {
        userSessionService.clearUserSession(userId);
    }
}