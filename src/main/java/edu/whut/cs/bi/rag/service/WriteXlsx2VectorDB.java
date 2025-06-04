package edu.whut.cs.bi.rag.service;
import edu.whut.cs.bi.rag.tools.Xlsx2TxtResource;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class WriteXlsx2VectorDB {
    @Autowired
    private VectorStore vectorStore;

    public String writeXlsx2VectorDB(String filePath) throws IOException {
        List<List<Map<String, Object>>> allSheetsData = Xlsx2TxtResource.parseExcel(filePath);
        String xlsxName = filePath.substring(filePath.lastIndexOf("/") + 1);
        String content = allSheetsData.toString();
        File file = File.createTempFile(xlsxName, ".txt");
        try (FileWriter fileWriter = new FileWriter(file);){
            fileWriter.write(content);
        }

        try {

            // 解析文件内容
            DocumentReader reader;
            TextReader textReader = new TextReader(new FileSystemResource(file));
            textReader.getCustomMetadata().put("filename", xlsxName);
            reader = textReader;

            List<Document> documents = reader.read();

            // 分割文档
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(documents);

            // 添加到向量存储
            vectorStore.add(splitDocuments); // 向量数据库存储逻辑
            return "xlsx 文件写入成功，已存储到向量库";
        } catch (Exception e) {
            return "文件处理失败：" + e.getMessage();
        }
    }
}
