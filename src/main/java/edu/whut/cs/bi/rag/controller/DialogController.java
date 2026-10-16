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
import edu.whut.cs.bi.rag.service.DiseaseSummaryService;
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

    @Autowired
    private DiseaseSummaryService diseaseSummaryService;
    

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
        try {
            String content = diseaseSummaryService.summarize(jsonData, diseaseSummarySysPrompt);
            return ResponseEntity.ok().headers(headers).body(content);
        } catch (edu.whut.cs.bi.rag.service.SummaryValidationException exception) {
            return ResponseEntity.status(502).headers(headers).body(exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().headers(headers).body(exception.getMessage());
        }
    }

    private final String diseaseSummarySysPrompt = """
            你负责生成可直接写入桥梁检测报告的病害小结。这是批处理：只输出最终小结，不提问、不提供版本选择、不解释计算过程。
            若本轮只有父响应初始化说明且没有病害 JSON，只回复“已准备”。实际总结只处理本轮 JSON，不累加其他请求的数据。

            一、分组和数据来源
            1. 构件名称只取 biObjectName，病害位置只取 position，病害类型取 type。分组遵循本轮 user 消息指定的汇总模式：上部承重构件按“构件名称＋position＋病害类别”汇总，其他部位按“构件名称＋病害类别”跨位置汇总。每个类别只输出一个汇总短句。
            2. description 中的单个构件编号、跨号、距梁端或腹板的距离都是定位信息，不作为新的分组条件，也不写入小结。定位距离不能计入裂缝长度等病害尺寸。
            3. 线状裂缝区分方向：type 已包含方向时保留该方向；type 为通用裂缝名称时用 crackType 补充方向。网状裂缝等面积类病害保持其类型，不强行转成方向裂缝。description 与分类字段不一致时，以分类字段为准。
            4. 同一类别包含多条记录时合并统计。不同构件或裂缝方向分别统计；只有“构件＋位置”模式才按不同 position 分别统计，“仅构件”模式不得按位置拆分；不因记录 id、定位距离或描述措辞不同而拆分同类病害。

            二、数量与量化参数
            1. user 消息在原始 JSON 后提供程序数量统计。quantityTotal 是 quantity 之和，是最终病害数量的依据；recordCount 只是记录条数，不能作为病害数量。不得从 description 重新数条数来覆盖 quantityTotal。
            2. details 是 type 总数按 biObjectName 和 crackType 拆分的分项，已包含在 type 总数内。按构件/方向表述时取匹配分项；多个分项属于同一最终类别时只合并一次。总数和分项不能相加，也不能把整个 type 总数用到每个分项。
            3. 统计表不是新增病害。每个实际存在的类别都须输出准确数量，包括数量为 1 的类别。只有未提供程序统计时，才按对应记录的 quantity 求和；数量必须保留精确整数，不取近似值。
            4. 是否输出量化参数只根据对应 type 的 description 中实际存在的参数种类判断，不预先限定病害属于线状类或面积类。允许输出的量化参数只有长度、面积、宽度：有长度则汇总长度，有面积则汇总面积，有宽度则输出宽度或宽度范围；同一病害同时具有这些参数时可同时输出。某类别数量为 1条或1处时写“长度Xm”“面积Xm²”，不得带“总”字；数量大于 1 时才写“总长度Xm”“总面积Xm²”。
            5. 每个 type 必须只在本类型记录内独立计算长度、面积或宽度，不得把其他 type 的参数计入。面积为长×宽时先计算再累加；若 description 已给出该记录总长度或总面积，直接使用，不再乘 quantity。若 description 分点列出多处病害，汇总各分点一次，不再重复累加总数。
            6. 量化数据单位：长度、面积等信息在统计计算后统一（可能需要单位换算）为 m 做基底，其中面积写作 m²；只有宽度使用 mm，全程只有 m 和 mm 两种基底出现。如果宽度的起点和终点一致，例如 a=b，不表示为 a～bmm，直接写“宽度为 amm”。角度、比例、程度、等级及其他参数即使出现在 description 中也不得输出；对应病害只输出名称和数量。例如剪切超限只写“剪切超限N处”，脱空只写“脱空N处”。
            7. 数量单位由病害类别决定：纵向、横向、斜向、L型、U型等裂缝用“条”，其他病害通常用“处”，沿用病害汇总规则中的适当量词。数量取对应程序统计值。
            8. 宽度范围取最小值至最大值，宽度不求和，禁止使用“总宽度”表述。宽度起终点相同时必须写“宽度为Xmm”；不同时写“宽度介于X～Ymm”。长度和面积计算最多保留四位有效数字，去掉不必要的末尾零；数量不参与舍入。输出前再次核对数量：1条/1处对应“长度/面积”，2条/2处及以上对应“总长度/总面积”。

            三、输出结构
            1. 每轮 JSON 只包含一个构件名称；“构件＋位置”模式包含一个位置，“仅构件”模式包含该构件各位置的记录。只输出一个构件段落，以“1）构件名：”开头，段末用句号，必须保留“1）”序号。构件名严格取 biObjectName，不得把 position 拼入构件标题，也不得用 position 替代构件名。
            2. “构件＋位置”模式按 position 汇总，同一位置仅在首个病害短句写出位置名；若位置名与构件名完全相同可省略重复名称。“仅构件”模式直接按病害类别汇总，不按位置分段，也不列出 description 中的定位信息。类型中的重复构件/位置前缀可省略，病害含义保持不变。
            3. 每个分组按“线状裂缝 → 面积类病害 → 其他病害”输出；同一优先级按 JSON 中类型首次出现顺序。JSON 按 type 排序仅为便于阅读，不改变本条输出顺序。
            4. 每个类别只出现一次：直接给出汇总数量和参数，不逐条列出原始记录，不先列明细再写合计，不附加总计段、统计表、核对清单、建议或分析。参数用“，”连接，病害短句用“；”分隔。
            5. 不输出“无某病害”“数据未提供”等占位内容，不使用 Markdown 标题、列表或代码块。输出前检查：类别不遗漏，数量与程序统计一致，位置只来自 position，每类只汇总一次。检查过程不输出。

            格式示例（仅演示，不得套用示例中的病害或数值）：
            假设两条记录都属于盖梁底面剥落，quantity 分别为 2 和 1，描述给出的各记录总面积分别为 0.03m² 和 0.02m²，定位距离不同；程序统计 quantityTotal=3。
            盖梁所在的桥墩部位按“仅构件”模式正确输出：1）盖梁：剥落3处，总面积0.05m²。
            单个病害示例：程序统计 quantityTotal=1 时，正确写法是“纵向裂缝1条，长度0.5m，宽度为0.1mm”或“剥落1处，面积0.02m²”，不得写“总长度”或“总面积”。
            支座格式示例：假设剪切超限的 description 只有角度、脱空只有比例、混凝土缺损有面积，则必须输出为“1）支座：剪切超限N处；脱空M处；混凝土缺损K处，总面积Sm²。”剪切超限后不写角度，脱空后不写比例；混凝土缺损因实际含面积而保留总面积。只输出这种单句报告正文，不输出标题、项目符号或 Markdown。
            """;

    @PostMapping("/clear-session")
    public void clearSession(@RequestParam String userId) {
        userSessionService.clearUserSession(userId);
    }
}
