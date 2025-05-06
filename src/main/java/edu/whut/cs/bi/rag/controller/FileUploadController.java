package edu.whut.cs.bi.rag.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class FileUploadController {
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private VectorStore vectorStore;

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
                reader = new PagePdfDocumentReader(new InputStreamResource(file.getInputStream()));
            } else if (file.getOriginalFilename().endsWith(".txt")) {
                TextReader textReader = new TextReader(new InputStreamResource(file.getInputStream()));
                textReader.getCustomMetadata().put("filename", file.getOriginalFilename());
                reader = textReader;
            } else {
                return "不支持的文件类型，仅支持 PDF 和 TXT";
            }
            List<Document> documents = reader.read();

            // 分割文档
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(documents);

            // 添加到向量存储（Milvus）
            vectorStore.add(splitDocuments); // 向量数据库存储逻辑

            return "文件上传成功，已存储到 Milvus 向量库";
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
                    "  - 行政识别数据（路线编号、路线名称、路线等级、桥梁编号、桥梁名称、桥位桩号、功能类型、被跨越道路名称、设计荷载、建成时间、设计单位、施工单位、管养单位 等）。\n" +
                    "  - 桥梁技术指标（桥梁全长、桥面总宽、设计洪水频率及水位、设计地震动峰值加速度系数 等）。\n" +
                    "  - 结构信息（桥梁分孔、结构体系、上部结构形式与材料、桥面系形式与材料、下部结构形式与材料、基础形式与材料、支座形式与材料 等）。\n" +
                    "  - 档案资料（设计图纸、设计文件、竣工图纸、施工文件、验收文件、行政审批文件、定期检查资料、特殊检查资料、历次维修加固资料、档案形式、建档时间（年/月）等\n" +
                    "  - 检测评定历史（包含评定时间、检测类别、评定结果、处治对策、下次检测时间 等）。\n" +
                    "  - 养护处治记录。\n" +
                    "需要说明的事项(含桥梁管养单位的变更情况) \n" +
                    "- 其他。\n" +
                    "- 请注意每个小字段前会有序号，该序号总体连续，偶有间断，不需要将小字段前的序号加入json，但生成json时注意和数据区分开来，不要弄混\n" +
                    "- 如果字段值为空，统一表示为 / \n" +
                    "- 请注意单个字也可以是字段名（如 左、右）\n" +
                    "请严格按照上述格式返回且仅返回JSON数据：\n\n" ;

            Prompt requestPrompt = new Prompt(prompt, OpenAiChatOptions.builder().build());
            return chatClient.prompt(requestPrompt).user(content.toString()).stream().content();

        }
        return Flux.empty();
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
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore, SearchRequest.builder().topK(5).build()))
                .build();

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
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore, SearchRequest.builder().topK(5).build()))
                .build();

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



