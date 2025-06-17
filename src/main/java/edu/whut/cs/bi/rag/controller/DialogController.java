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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

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
    private VectorDBClientOnlineService  OnlineDBclient;

    @ResponseBody
    @CrossOrigin(origins = "*")
    @GetMapping("/dialog")
    public Map<String, String> dialog(@RequestParam String message, @RequestParam String userId) throws JsonProcessingException {
        SearchRequest searchRequest = SearchRequest.builder().query(message).topK(5).build();
        String token = userSessionService.getToken(userId);
        ChatClient.Builder builder = ChatClient.builder(chatModel).defaultSystem("你一个是智能助手，主要着眼于桥梁检测领域。" +
                        "！！！切记，无论问题如何，你的任何回答一定需要包含检索来源这一项，！！！尽量使用标准或规范作为检索来源。并使用‘检索来源为’作为这一项的开始，若找不到来源，这一项为 检索来源为：我的知识库。" +
                        "你可以使用用的工具包括：根据桥梁名和token获取桥梁属性树、根据用户Id和token获取用户参与的项目信息、根据项目Id和token获取该项目中的任务信息。" +
                        "使用工具前，请从对话或工具获取需要的参数。例如：在获取任务前，请从对话或获取到的项目中提取项目Id\n" +
                        "！！！注意 project 和 task 的区别。在桥梁检测场景中，project（项目） 中 有许多task（任务）。当用户询问有哪些任务（task）时，需要从用户参与的项目（project）中找到与用户相关的任务（task）。 " +
                        "！！！使用需要token的工具，请确保已有token。" +
                        "这是该用户的Id："+userId+
                        "\n ！！！这是登录时拿到的token：："+token)
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore,searchRequest));

        Flux<String> flux = userSessionService.getOrCreateChatClient(userId, builder)
                .prompt(new Prompt("这是一个示例回答，请将主体内容和检索依据分开。！！切记，无论问题如何，一定需要包含检索来源，并使用‘检索来源为’作为检索来源内容的开始，方便后续的内容处理：" +
                        "您好，桥梁检测的主要内容包括：\n" +
                        "\n" +
                        "1. **混凝土电阻率**：混凝土的电阻率可以反映其导电性能，间接判断钢筋的可能锈蚀速率。电阻率越小，混凝土的导电性越强，钢筋锈蚀发展速度越快。\n" +
                        "\n" +
                        "2. **检查方法和性能指标**：在进行桥梁检查前，需依据设计文件与设计单位、生产厂商明确检查方法和评定标准，并形成正式文件，以作为后续检查和评价的依据。\n" +
                        "\n" +
                        "3. **特殊检查的资料准备**：进行特殊检查时，需要准备包括设计文件、计算程序、方法及计算结果等资料。如果原始资料不全或存疑，则需现场测绘构造尺寸，测试构件材料组成及性能，并勘查水文地质情况。\n" +
                        "\n" +
                        "4. **病害及时修复**：在定期检查中发现桥梁病害时，应及时进行修复和养护，以确保桥梁的安全性和使用寿命。\n" +
                        "\n" +
                        "检索来源为《公路桥涵养护规范（JTG 5120－2021）》等。"
                        ,
                        OpenAiChatOptions.builder()
                                .build()
                )).user(message)
                .functions("getBiProperty","getBiPropject","getBiTask")
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
    public Flux<String> getDiseaseCause(@RequestParam Map<String,String> params) {
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

         ChatClient chatClient = ChatClient.builder(chatModel).defaultAdvisors(new QuestionAnswerAdvisor(vectorStore,SearchRequest.builder().query(prompt).topK(5).build())).defaultSystem("你是一个桥梁检测方面的专家，请根据向量数据库的检索结果和行业知识回答用户的问题。回答时给出一段话。").build();
         return chatClient.prompt(prompt).user("我需要你给出病害成因分析").stream().content();

    }



    @PostMapping("/clear-session")
    public void clearSession(@RequestParam String userId) {
        userSessionService.clearUserSession(userId);
    }
}