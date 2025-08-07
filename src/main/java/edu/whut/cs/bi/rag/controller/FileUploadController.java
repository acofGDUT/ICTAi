package edu.whut.cs.bi.rag.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.AIDatabase;
import com.tencent.tcvectordb.model.CollectionView;
import com.tencent.tcvectordb.model.param.collectionView.FileType;
import com.tencent.tcvectordb.model.param.collectionView.LoadAndSplitTextParam;
import com.tencent.tcvectordb.model.param.collectionView.ParsingProcessParam;
import com.tencent.tcvectordb.model.param.collectionView.SplitterPreprocessParams;
import com.tencent.tcvectordb.model.param.enums.ParsingTypeEnum;
import edu.whut.cs.bi.rag.service.VectorDBClientOnlineService;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api-ai")
public class FileUploadController {
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private VectorStore vectorStore;

    // 在线 向量数据库 测试
    @Autowired
    private VectorDBClientOnlineService OnlineDBclient;

    private static final int CHUNK_SIZE = 1000; // 每块的大小（可以根据实际情况调整）
    // 将 传入的标准 文档 分片， 存入向量数据库。
    @PostMapping("/upload")
    public String uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            // 检查文件是否为空
            if (file.isEmpty()) {
                return "文件为空，请上传有效文件";
            }

            // 解析文件内容，支持多种文件类型
            DocumentReader reader;
            if (file.getOriginalFilename().endsWith(".pdf")) {

                reader = new PagePdfDocumentReader(new InputStreamResource(file.getInputStream()), PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0)
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(0)
                                .withNumberOfBottomTextLinesToDelete(0)
                                .build())
                        .withPagesPerDocument(1)
                        .build());
            } else if (file.getOriginalFilename().endsWith(".txt")) {
                TextReader textReader = new TextReader(new InputStreamResource(file.getInputStream()));
                textReader.getCustomMetadata().put("filename", file.getOriginalFilename());
                reader = textReader;
            } else {
                return "不支持的文件类型，仅支持 PDF 和 TXT";
            }
            List<Document> documents = reader.read();
            
            // 过滤掉所有不可见的字符
            List<Document> cleanedDocuments = documents.stream()
                    .map(doc -> {
                        String cleanedContent = doc.getText().replaceAll("[\\p{C}\\p{Zl}\\p{Zp}]", " ");
                        return new Document(doc.getId(), cleanedContent, doc.getMetadata());
                    })
                    .collect(Collectors.toList());

            // 分割文档
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(cleanedDocuments);
            // 添加到向量存储
            vectorStore.add(splitDocuments);
            
            return "文件上传成功，已存储到向量库";
        } catch (IOException e) {
            return "文件读取失败：" + e.getMessage();
        } catch (Exception e) {
            return "文件处理失败：" + e.getMessage();
        }
    }




    // 当前可用的方法 ： 流式返回，解决了超时问题
    @PostMapping("/word2Json")
    public Flux<String> word2Json(@RequestParam("file") MultipartFile file) throws IOException {
        if (file != null && !file.isEmpty()) {
            XWPFDocument document = new XWPFDocument(file.getInputStream());
            StringBuilder content = new StringBuilder();

            // 解析文档中的段落
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                content.append(paragraph.getText()).append("\n");
            }

            // 解析文档中的表格
            for (XWPFTable table : document.getTables()) {
                for (int i = 0; i < table.getNumberOfRows(); i++) {
                    boolean flag = false; // 是否是特殊字段
                    XWPFTableRow row = table.getRow(i);
                    for (int j = 0; j < row.getTableCells().size(); j++) {
                        XWPFTableCell cell = row.getCell(j);
                        String cellText = cell.getText();

                        // 特殊处理“设计洪水频率及其水位”字段
                        if (cellText.contains("设计洪水频率及其水位")) {
                            flag = true;
                            // 获取当前字段 第一个值
                            String currentValue = cellText+"\t"+row.getCell(j+1).getText();
                            // 获取下一个的值（如果存在）
                            String nextRowValue = table.getRow(i+1).getCell(j+1).getText();
                            // 拼接当前值和下一行的值
                            cellText = currentValue + "," + nextRowValue;
                            j++;
                        }
                        content.append(cellText).append("\t");

                    }
                    content.append("\n");
                    if(flag){
                        i++;// 跳过特殊的行
                    }
                }
            }
//            System.out.println(content);
            document.close();

            // 调用模型将内容转换为JSON
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .build();

            String prompt = "请将以下桥梁卡片内容（表格）解析为JSON格式，完整JSON格式如下：\n" +
                    "- 根节点为桥梁名称。\n" +
                    "- 包含以下字段：\n" +
                    "  - 行政识别数据（路线编号、路线名称、路线等级、桥梁编号、桥梁名称、桥位桩号、功能类型、被跨越道路名称、设计荷载、建成时间、设计单位、施工单位、管养单位 等）。\n" +
                    "  - 桥梁技术指标（桥梁全长、桥面总宽、设计洪水频率及水位、设计地震动峰值加速度系数 等）。\n" +
                    "  - 结构信息（桥梁分孔、结构体系、上部结构形式与材料、桥面系形式与材料、下部结构形式与材料、基础形式与材料、支座形式与材料 等）。\n" +
                    "  - 档案资料（设计图纸、设计文件、竣工图纸、施工文件、验收文件、行政审批文件、定期检查资料、特殊检查资料、历次维修加固资料、档案形式、建档时间（年/月）等\n" +
                    "  - 检测评定历史（包含评定时间、检测类别、评定结果、处治对策、下次检测时间 等）。\n" +
                    "  - 养护处治记录。\n" +
                    "需要说明的事项(含桥梁管养单位的变更情况) \n" +
                    "- 其他。\n" +
                    "- 请注意部分小字段前会有序号，该序号总体连续，偶有间断，不需要将小字段前的序号加入json，但生成json时注意和数据区分开来，不要弄混\n" +
                    "- 如果字段值为空，统一表示为 / 。！！！如果某个字段下有子字段，尽管所有子字段都为空，也需要将子字段加入json。！！！\n" +
                    "- 请注意单个字也可以是字段名（如 左、右）\n" +
                    "- 表格某些字段有多数据，上下分布（如 设计洪水频率及其水位），对于每个字段，在填入数据前，分析其有单一数据或多数据，将落单数据合理地放入多数据的字段中。\n" +
                    "- 如果 引道线形或曲线半径(m) 字段的值为无，请加上一个值0" +
                    "以下为示例json:" +
                    "{\n" +
                    "  \"许家棚大桥-上行\": {\n" +
                    "  \"桥梁所处行政区划代码\":"+"\"420302\",\n"+
                    "    \"行政识别数据\": {\n" +
                    "      \"路线编号\": \"G70\",\n" +
                    "      \"路线名称\": \"福州至银川高速公路\",\n" +
                    "      \"路线等级\": \"国道、高速公路\",\n" +
                    "      \"桥梁编号\": \"G70420302R1020\",\n" +
                    "      \"桥梁名称\": \"许家棚大桥-上行\",\n" +
                    "      \"桥位桩号\": \"1373.765\",\n" +
                    "      \"功能类型\": \"0\",\n" +
                    "      \"被跨越道路名称\": \"道路\",\n" +
                    "      \"被跨越道路桩号\": \"K1373+765\",\n" +
                    "      \"设计荷载\": \"公路Ⅰ级\",\n" +
                    "      \"建成时间\": \"2007-12-30\",\n" +
                    "      \"设计单位\": \"湖北省交通规划设计院\",\n" +
                    "      \"施工单位\": \"湖北省路桥有限责任公司\",\n" +
                    "      \"监理单位\": \"铁四院工程监理咨询公司/武汉市\",\n" +
                    "      \"业主单位\": \"湖北交投鄂西北高速公路运营管理有限公司\",\n" +
                    "      \"管养单位\": \"湖北交投鄂西北高速公路运营管理有限公司\"\n" +
                    "    },\n" +
                    "    \"桥梁技术指标\": {\n" +
                    "      \"桥梁全长(m)\": \"155.6\",\n" +
                    "      \"桥面总宽(m)\": \"12\",\n" +
                    "      \"车道宽度(m)\": \"7.5\",\n" +
                    "      \"人行道宽度(m)\": \"0\",\n" +
                    "      \"护栏或防撞墙高度(m)\": \"1\",\n" +
                    "      \"中央分隔带宽度(m)\": \"0\",\n" +
                    "      \"桥面标准净空(m)\": \"22\",\n" +
                    "      \"桥面实际净空(m)\": \"22\",\n" +
                    "      \"桥下通航等级及标准净空(m)\": \"40\",\n" +
                    "      \"桥下实际净空\": \"8.5\",\n" +
                    "      \"引道总宽(m)\": \"0\",\n" +
                    "      \"引道线形或曲线半径(m)\": \"无,0\",\n" +
                    "      \"设计洪水频率及其水位\": \"1/100,0\",\n" +
                    "      \"历史洪水位\": \"0\",\n" +
                    "      \"设计地震动峰值加速度系数\": \"0.05\",\n" +
                    "      \"桥面高程(m)\": \"/\"\n" +
                    "    },\n" +
                    "    \"结构信息\": {\n" +
                    "      \"桥梁分孔\": \"5*30\",\n" +
                    "      \"结构体系\": \"连续箱梁\",\n" +
                    "      \"上部结构形式与材料\": {\n" +
                    "        \"主梁\": {\n" +
                    "          \"序号\": \"1\",\n" +
                    "          \"孔号\": \"1-5\",\n" +
                    "          \"结构形式\": \"连续箱梁\",\n" +
                    "          \"材料\": \"预应力混凝土\"\n" +
                    "        }\n" +
                    "      },\n" +
                    "      \"桥面系形式与材料\": {\n" +
                    "        \"桥面铺装\": {\n" +
                    "          \"序号\": \"1\",\n" +
                    "          \"孔号\": \"1-5\",\n" +
                    "          \"材料\": \"沥青混凝土\"\n" +
                    "        },\n" +
                    "        \"伸缩缝\": [\n" +
                    "          {\n" +
                    "            \"序号\": \"1\",\n" +
                    "            \"位置\": \"1-4\",\n" +
                    "            \"形式材料\": \"无\"\n" +
                    "          },\n" +
                    "          {\n" +
                    "            \"序号\": \"2\",\n" +
                    "            \"位置\": \"0,5\",\n" +
                    "            \"形式材料\": \"模数式伸缩装置\"\n" +
                    "          }\n" +
                    "        ],\n" +
                    "        \"人行道、路缘\": {\n" +
                    "          \"左\": \"/\",\n" +
                    "          \"右\": \"/\"\n" +
                    "        },\n" +
                    "        \"栏杆、护栏\": {\n" +
                    "          \"中护栏\": {\n" +
                    "            \"护栏等级\": \"SA\"\n" +
                    "          },\n" +
                    "          \"边护栏\": {\n" +
                    "            \"护栏等级\": \"SA\"\n" +
                    "          }\n" +
                    "        },\n" +
                    "        \"照明、标志\": \"有\"\n" +
                    "      },\n" +
                    "      \"下部结构形式与材料\": {\n" +
                    "        \"桥台\": [\n" +
                    "          {\n" +
                    "            \"序号\": \"1\",\n" +
                    "            \"桥台号\": \"5\",\n" +
                    "            \"形式\": \"肋板式桥台\",\n" +
                    "            \"材料\": \"钢筋混凝土\"\n" +
                    "          },\n" +
                    "          {\n" +
                    "            \"序号\": \"2\",\n" +
                    "            \"桥台号\": \"0\",\n" +
                    "            \"形式\": \"桩柱式桥台\",\n" +
                    "            \"材料\": \"钢筋混凝土\"\n" +
                    "          }\n" +
                    "        ],\n" +
                    "        \"桥墩\": {\n" +
                    "          \"序号\": \"1\",\n" +
                    "          \"桥墩号\": \"1-4\",\n" +
                    "          \"形式\": \"其他\",\n" +
                    "          \"材料\": \"钢筋混凝土\"\n" +
                    "        },\n" +
                    "        \"锥坡、护坡\": {\n" +
                    "          \"左（小桩号侧）\": \"有\",\n" +
                    "          \"右（小桩号侧）\": \"有\",\n" +
                    "          \"左（大桩号侧）\": \"有\",\n" +
                    "          \"右（大桩号侧）\": \"有\"\n" +
                    "        },\n" +
                    "        \"翼墙、耳墙\": {\n" +
                    "          \"左（小桩号侧）\": \"有\",\n" +
                    "          \"右（小桩号侧）\": \"有\",\n" +
                    "          \"左（大桩号侧）\": \"有\",\n" +
                    "          \"右（大桩号侧）\": \"有\"\n" +
                    "        }\n" +
                    "      },\n" +
                    "      \"基础形式与材料\": {\n" +
                    "        \"基础\": {\n" +
                    "          \"序号\": \"1\",\n" +
                    "          \"墩台号\": \"0,5,1-4\",\n" +
                    "          \"形式\": \"挖孔灌注桩基础\",\n" +
                    "          \"材料\": \"钢筋混凝土\"\n" +
                    "        },\n" +
                    "        \"锚碇（悬索桥）\": {\n" +
                    "         \"位置\":\"/\",\n" +
                    "        \"形式\":\"/\",\n" +
                    "         \"材料\":\"/\"\n" +
                    "}\n" +
                    "      },\n" +
                    "      \"支座形式材料与附属设施\": {\n" +
                    "        \"支座\": {\n" +
                    "          \"序号\": \"1\",\n" +
                    "          \"位置\": \"0,5,1-4\",\n" +
                    "          \"形式\": \"球形支座\",\n" +
                    "          \"材料\": \"/\"\n" +
                    "        },\n" +
                    "        \"桥梁防撞设施\": {\"位置\":\"/\", \"形式\":\"/\",\"材料\":\"/\" }" +
                    "   ,\n" +
                    "        \"航标及排水系统\": {\n" +
                    "          \"航标\": {\"位置\":\"/\",\"形式\":\"/\"},\n" +
                    "          \"排水系统\": {\n" +
                    "            \"位置\": \"/\",\n" +
                    "            \"形式\": \"进水口接排水管和落水管沿桥墩下排\"\n" +
                    "          }\n" +
                    "        },\n" +
                    "        \"调治构造物\": \"有\"\n" +
                    "      }\n" +
                    "    },\n" +
                    "    \"档案资料\": {\n" +
                    "      \"设计图纸\": \"/\",\n" +
                    "      \"设计文件\": \"/\",\n" +
                    "      \"竣工图纸\": \"/\",\n" +
                    "      \"施工文件\": \"/\",\n" +
                    "      \"验收文件\": \"/\",\n" +
                    "      \"行政审批文件\": \"/\",\n" +
                    "      \"定期检查资料\": \"/\",\n" +
                    "      \"特殊检查资料\": \"/\",\n" +
                    "      \"历次维修加固资料\": \"/\",\n" +
                    "      \"其他档案\": \"/\",\n" +
                    "      \"档案形式\": \"/\",\n" +
                    "      \"建档时间\": \"/\"\n" +
                    "    },\n" +
                    "    \"检测评定历史\": \n" +
                    "      [{\n" +
                    "        \"评定时间\": \"/\",\n" +
                    "        \"检测类别\": \"/\",\n" +
                    "        \"评定结果\": \"/\",\n" +
                    "        \"处治对策\": \"/\",\n" +
                    "        \"下次检测时间\": \"/\"\n" +
                    "      }]\n" +
                    "    ,\n" +
                    "    \"养护处治记录\":" +
                    "       [{ \"时间（段）\": \"/\", " +
                    " \"处治类别（维修、加固、改造）\"：\"/\","+
                    "\"处治原因\":\"/\","+
                    "\"处治范围\":\"/\","+
                    "\"工程费用（万元）\":\"/\","+
                    "\"经费来源\":\"/\","+
                    "\"处治质量评定\":\"/\","+
                    "\"建设单位\":\"/\","+
                    "\"设计单位\":\"/\","+
                    "\"施工单位\":\"/\","+
                    "\"监理单位\":\"/\""+
                    "}],\n" +
                    "    \"需要说明的事项\": \"/\",\n" +
                    "    \"其他\": {\n" +
                    "      \"桥梁总体照片\": \"/\",\n" +
                    "      \"桥梁正面照片\": \"/\",\n" +
                    "      \"桥梁工程师\": \"程生宏\",\n" +
                    "      \"填卡人\": \"/\",\n" +
                    "      \"填卡日期\": \"2025-04-11\"\n" +
                    "    }\n" +
                    "  }\n" +
                    "}"+
                    "请严格按照上述要求并参考示例返回且仅返回JSON数据：\n\n" ;


            Prompt requestPrompt = new Prompt(prompt, OpenAiChatOptions.builder().build());
            return chatClient.prompt(requestPrompt).user(content.toString()).stream().content();

        }
        return Flux.empty();
    }





    // 腾讯云 在线数据库 ， 暂时弃用
    @PostMapping("/uploadToOnlineVector")
    public String  uploadFileToOnlineVector(@RequestParam("file") MultipartFile file) {
        try {
            VectorDBClient client = OnlineDBclient.getDBClient();
            // 初始化,第一次时需创建
            OnlineDBclient.initDBandCollection(client);
            //拿到 数据库和集合
            AIDatabase db = client.aiDatabase(OnlineDBclient.dbName);
            CollectionView collection = db.describeCollectionView(OnlineDBclient.collectionName);
            //设置 参数
            LoadAndSplitTextParam param = LoadAndSplitTextParam.newBuilder()
                    .withFileInputStream(file.getInputStream())
                    .withFileType(FileType.PDF)
                    .withDocumentSetName(file.getName())
                    .withInputStreamDataSize(file.getSize())
                    .withSplitterProcess(SplitterPreprocessParams.newBuilder()
                            .withAppendKeywordsToChunkEnum(true)
                            .withAppendTitleToChunkEnum(false)
                            .Build())
                    .withParsingProcess(ParsingProcessParam.newBuilder().withParsingType(ParsingTypeEnum.VisionModel).build())
                    .Build();

            // 调用 loadAndSplitText() 上传文件
            collection.loadAndSplitText(param);
            return "文件成功上传到在线向量数据库";
        } catch (Exception e){
            return "上传失败" + e.getMessage();
        }
    }








    // 当前正确性 不可用 ，采用 分块 发送再合并的方式 ， 分块会丢失信息。
    @PostMapping("/word2Json_2")
    public Map<String, Object> uploadWord_2(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new java.util.HashMap<>();
        try {
            if (file != null && !file.isEmpty()) {
                XWPFDocument document = new XWPFDocument(file.getInputStream());
                StringBuilder content = new StringBuilder();

                // 解析文档中的段落
                for (XWPFParagraph paragraph : document.getParagraphs()) {
                    content.append(paragraph.getText()).append("\n");
                }

                // 解析文档中的表格
                for (XWPFTable table : document.getTables()) {
                    for (int i = 0; i < table.getNumberOfRows(); i++) {
                        XWPFTableRow row = table.getRow(i);
                        for (int j = 0; j < row.getTableCells().size(); j++) {
                            XWPFTableCell cell = row.getCell(j);
                            content.append(cell.getText()).append("\t");
                        }
                        content.append("\n");
                    }
                }

                document.close();

                String documentContent = content.toString();

                // 分块处理
                List<String> chunks = new ArrayList<>();
                for (int i = 0; i < documentContent.length(); i += CHUNK_SIZE) {
                    chunks.add(documentContent.substring(i, Math.min(i + CHUNK_SIZE, documentContent.length())));
                }

                // 使用线程池处理分块
                ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                List<CompletableFuture<String>> futures = new ArrayList<>();
                AtomicInteger counter = new AtomicInteger(0);

                for (String chunk : chunks) {
                    int index = counter.getAndIncrement();
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            return processChunk(chunk, index);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, executor));
                }

                // 合并结果
                // 修改合并部分代码（替换原有字符串拼接逻辑）
                ObjectMapper mapper = new ObjectMapper();
                JsonNode mainJson = null; // 主JSON（index=0）
                Map<String, JsonNode> otherNodes = new HashMap<>(); // 其他分块的节点

                for (CompletableFuture<String> future : futures) {
                    String partialResult = future.get();
                    Pattern pattern = Pattern.compile(
                            "##INDEX##(\\d+)\\s*```json\\s*([\\s\\S]*?)\\s*```",
                            Pattern.DOTALL
                    );
                    Matcher matcher = pattern.matcher(partialResult);
                    if (matcher.find()) {
                        int index = Integer.parseInt(matcher.group(1));
                        String jsonStr = matcher.group(2).trim();
                        JsonNode node = mapper.readTree(jsonStr);

                        if (index == 0) {
                            mainJson = node; // 主结构以index=0为准
                        } else {
                            // 将其他分块视为根节点下的补充内容
                            otherNodes.putAll(flattenJsonNode(node));
                        }
                    }
                }
                executor.shutdown();
                executor.awaitTermination(1, TimeUnit.MINUTES);
                // 合并到主JSON
                if (mainJson != null) {
                    ObjectNode mainObject = (ObjectNode) mainJson;
                    otherNodes.forEach((fieldName, fieldValue) -> {
                        if (mainObject.has(fieldName)) {
                            // 深度合并已有字段
                            JsonNode existingNode = mainObject.get(fieldName);
                            JsonNode mergedNode = mergeNodes(existingNode, fieldValue);
                            mainObject.set(fieldName, mergedNode);
                        } else {
                            // 直接添加新字段
                            mainObject.set(fieldName, fieldValue);
                        }
                    });
                    result.put("jsonResponse", mainObject.toPrettyString());
                }
            }
        } catch (Exception e) {
            result.put("error", "文件解析失败：" + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }
    // 服务于  /word2Json_2
    private Map<String, JsonNode> flattenJsonNode(JsonNode node) {
        Map<String, JsonNode> map = new HashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                map.put(entry.getKey(), entry.getValue());
            });
        }
        return map;
    }
    // 服务于  /word2Json_2
    private JsonNode mergeNodes(JsonNode mainNode, JsonNode updateNode) {
        if (mainNode == null) return updateNode;
        if (!mainNode.isObject() || !updateNode.isObject()) {
            return updateNode; // 非对象直接覆盖
        }

        ObjectNode mergedNode = ((ObjectNode) mainNode).deepCopy();
        updateNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (mergedNode.has(key)) {
                // 递归合并子节点
                mergedNode.set(key, mergeNodes(mergedNode.get(key), value));
            } else {
                mergedNode.set(key, value);
            }
        });
        return mergedNode;
    }
    // 服务于  /word2Json_2
    public String processChunk(String chunk, int index) throws Exception {
        // 调用模型将内容转换为JSON
        System.out.println( "Processing chunk " + index );
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        String prompt = "你是一个格式转换专家，假设有多个你合作将分散有序的word片段转换成json格式，请将以下桥梁卡片部分内容解析为JSON格式，完整JSON格式如下：\n" +
                "- 根节点为桥梁名称。\n" +
                "- 包含以下字段：\n" +
                "  - 行政识别数据（路线编号、路线名称、路线等级、桥梁编号、桥梁名称、桥位桩号、功能类型、被跨越道路名称、设计荷载、建成时间、设计单位、施工单位、管养单位）。\n" +
                "  - 桥梁技术指标（桥梁全长、桥面总宽、设计洪水频率及水位、设计地震动峰值加速度系数）。\n" +
                "  - 结构信息（桥梁分孔、结构体系、上部结构形式与材料、桥面系形式与材料、下部结构形式与材料、基础形式与材料、支座形式与材料）。\n" +
                "  - 档案资料（设计图纸、设计文件、竣工图纸、施工文件、验收文件、行政审批文件、定期检查资料、特殊检查资料、历次维修加固资料、档案形式）。\n" +
                "  - 检测评定历史（包含评定时间、检测类别、评定结果、处治对策、下次检测时间）。\n" +
                "  - 养护处治记录。\n" +
                "文档最后的 其他 和 需要说明的事项 不需要加入json。请参考上述格式返回能够拼接的部分JSON数据，遇到开头或结尾被截断的情况，请保留开头或结尾的内容，保证最终可以拼成完整的json。这是index为"+index+"的数据。在结果前添加 '##INDEX##' 跟该部分的顺序索引。例，某个你返回的内容：\n"
                +"```##INDEX##1\n" +
                "```json\n" +
                "{\n" +
                "  \"档案资料\": {\n" +
                "    \"施工文件\": \"含施工缺陷处理\",\n" +
                "    \"验收文件\": \"\",\n" +
                "    \"行政审批文件\": \"\",\n" +
                "    \"定期检查资料\": \"\",\n" +
                "    \"特殊检查资料\": \"\",\n" +
                "    \"历次维修加固资料\": \"\",\n" +
                "    \"档案形式\": \"\",\n" +
                "    \"建档时间\": \"年/月\"\n" +
                "  },\n" +
                "  \"检测评定历史\": [\n" +
                "    {\n" +
                "      \"评定时间\": \"2024-09-12\",\n" +
                "      \"检测类别\": \"定期检查\",\n" +
                "      \"评定结果\": \"2类\",\n" +
                "      \"处治对策\": \"\",\n" +
                "      \"下次检测时间\": \"2027-09-12\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"评定时间\": \"2021-09-12\",\n" +
                "      \"检测类别\": \"定期检查\",\n" +
                "      \"评定结果\": \"1类\",\n" +
                "      \"处治对策\": \"\",\n" +
                "      \"下次检测时间\": \"2024-09-12\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"评定时间\": \"2020-09-24\",\n" +
                "      \"检测类别\": \"定期检查\",\n" +
                "      \"评定结果\": \"1类\",\n" +
                "      \"处治对策\": \"\",\n" +
                "      \"下次检测时间\": \"2023-09-24\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"养护处治记录\": []\n" +
                "}\n" +
                "```";

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .build();

        Prompt requestPrompt = new Prompt(prompt, options);
        return chatClient.prompt(requestPrompt).user(chunk).call().content();
    }

    //  将 多块 信息提问 ，让大模型合并 ， 曾经服务于 /word2Json_2 ，解决不了超时，已弃用
    private String mergeChunks(List<CompletableFuture<String>> futures) throws ExecutionException, InterruptedException {
        StringBuilder msg = new StringBuilder();
        for (CompletableFuture<String> future : futures) {
            System.out.println("Processing chunk " + future.get());
            msg.append(future.get());
        }
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        String prompt = "你是一个格式转换专家，假设有多个你已经将分散有序的word片段转换成json格式，请将汇总的内容合并为完整的JSON格式，完整JSON格式如下：\n" +
                "- 根节点为桥梁名称。\n" +
                "- 包含以下字段：\n" +
                "  - 行政识别数据（路线编号、路线名称、路线等级、桥梁编号、桥梁名称、桥位桩号、功能类型、被跨越道路名称、设计荷载、建成时间、设计单位、施工单位、管养单位）。\n" +
                "  - 桥梁技术指标（桥梁全长、桥面总宽、设计洪水频率及水位、设计地震动峰值加速度系数）。\n" +
                "  - 结构信息（桥梁分孔、结构体系、上部结构形式与材料、桥面系形式与材料、下部结构形式与材料、基础形式与材料、支座形式与材料）。\n" +
                "  - 档案资料（设计图纸、设计文件、竣工图纸、施工文件、验收文件、行政审批文件、定期检查资料、特殊检查资料、历次维修加固资料、档案形式）。\n" +
                "  - 检测评定历史（包含评定时间、检测类别、评定结果、处治对策、下次检测时间）。\n" +
                "  - 养护处治记录。\n" +
                "请参考上述格式返回完整的json。\n";

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .build();

        Prompt requestPrompt = new Prompt(prompt, options);
        return chatClient.prompt(requestPrompt).user(msg.toString()).call().content();
    }




    // 单次提问的方式，多次超时，暂时废弃
    @PostMapping("/word2Json_1")
    public Map<String, Object> uploadWord_1(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (file != null && !file.isEmpty()) {
                XWPFDocument document = new XWPFDocument(file.getInputStream());
                StringBuilder content = new StringBuilder();

                // 解析文档中的段落
                for (XWPFParagraph paragraph : document.getParagraphs()) {
                    content.append(paragraph.getText()).append("\n");
                }

                // 解析文档中的表格
                for (XWPFTable table : document.getTables()) {
                    for (int i = 0; i < table.getNumberOfRows(); i++) {
                        XWPFTableRow row = table.getRow(i);
                        for (int j = 0; j < row.getTableCells().size(); j++) {
                            XWPFTableCell cell = row.getCell(j);
                            content.append(cell.getText()).append("\t");
                        }
                        content.append("\n");
                    }
                }
                System.out.println(content);
                document.close();

                // 调用模型将内容转换为JSON
                ChatClient chatClient = ChatClient.builder(chatModel)
                        .build();

                String prompt = "请将以下桥梁卡片内容解析为JSON格式，完整JSON格式如下：\n" +
                        "- 根节点为桥梁名称。\n" +
                        "- 包含以下字段：\n" +
                        "  - 行政识别数据（路线编号、路线名称、路线等级、桥梁编号、桥梁名称、桥位桩号、功能类型、被跨越道路名称、设计荷载、建成时间、设计单位、施工单位、管养单位）。\n" +
                        "  - 桥梁技术指标（桥梁全长、桥面总宽、设计洪水频率及水位、设计地震动峰值加速度系数）。\n" +
                        "  - 结构信息（桥梁分孔、结构体系、上部结构形式与材料、桥面系形式与材料、下部结构形式与材料、基础形式与材料、支座形式与材料）。\n" +
                        "  - 档案资料（设计图纸、设计文件、竣工图纸、施工文件、验收文件、行政审批文件、定期检查资料、特殊检查资料、历次维修加固资料、档案形式）。\n" +
                        "  - 检测评定历史（包含评定时间、检测类别、评定结果、处治对策、下次检测时间）。\n" +
                        "  - 养护处治记录。\n" +
                        "- 不包含其他字段。\n" +
                        "请严格按照上述格式返回且仅返回JSON数据：\n\n" ;

                Prompt requestPrompt = new Prompt(prompt, OpenAiChatOptions.builder().build());
                String jsonResponse = chatClient.prompt(requestPrompt).user(content.toString()).call().content();

                result.put("jsonResponse", jsonResponse);
            }
        } catch (Exception e) {
            result.put("error", "文件解析失败：" + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }


}



