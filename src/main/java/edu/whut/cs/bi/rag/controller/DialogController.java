package edu.whut.cs.bi.rag.controller;

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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DialogController {
    @Autowired
    private ChatModel chatModel;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private UserSessionService userSessionService;

    @Autowired
    private VectorDBClientOnlineService  OnlineDBclient;

    @GetMapping("/dialog")
    public String dialog(@RequestParam String message, @RequestParam String userId) {
        SearchRequest searchRequest = SearchRequest.builder().query(message).topK(5).build();

        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore, searchRequest));

        return userSessionService.getOrCreateChatClient(userId, builder)
                .prompt(new Prompt(
                        "这是用户的提问："+message,
                        OpenAiChatOptions.builder()
                                .build()
                )).system("你一个是全面的助手，主要着眼于桥梁检测领域，请参考行业知识和向量数据库知识回答用户。")
                .call()
                .content();
    }



    // 腾讯云 在线数据库 ， 暂时弃用
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
                        "你现在的身份是桥梁检测助手，可以参考向量数据库中的相关标准和常识回答用户提问。"+ragPrompt,
                        OpenAiChatOptions.builder()
                                .build()
                ))
                .user(message)
                .call()
                .content();
    }


    @GetMapping("/disease/cause")
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
                 "分析该病害的可能成因，输出简洁易懂的成因分析结论。";

         ChatClient chatClient = ChatClient.builder(chatModel).defaultAdvisors(new QuestionAnswerAdvisor(vectorStore,SearchRequest.builder().query(prompt).topK(10).build())).defaultSystem("你是一个桥梁检测方面的专家，请根据向量数据库的检索结果和行业知识回答用户的问题。注意回答精简。").build();
         return chatClient.prompt(prompt).user("我需要你给出病害成因分析").stream().content();

    }



    @PostMapping("/clear-session")
    public void clearSession(@RequestParam String userId) {
        userSessionService.clearUserSession(userId);
    }
}