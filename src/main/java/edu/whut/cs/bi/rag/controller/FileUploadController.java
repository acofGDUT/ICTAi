package edu.whut.cs.bi.rag.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/api")
public class FileUploadController {
    @Autowired
    private VectorStore vectorStore;

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
}