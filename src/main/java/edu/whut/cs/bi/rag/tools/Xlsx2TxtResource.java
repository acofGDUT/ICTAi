package edu.whut.cs.bi.rag.tools;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelReader;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.read.builder.ExcelReaderBuilder;
import com.alibaba.excel.read.builder.ExcelReaderSheetBuilder;
import com.alibaba.excel.read.metadata.ReadSheet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Xlsx2TxtResource {
    public static List<List<Map<String, Object>>> parseExcel(String filePath) {
        List<List<Map<String, Object>>> allSheetsData = new ArrayList<>();
        try (ExcelReader excelReader = EasyExcel.read(new File(filePath)).build()) {
            // 获取所有表名
            List<ReadSheet> sheets = excelReader.excelExecutor().sheetList();
            List<String> sheetNames = new ArrayList<>();
            for (ReadSheet sheet : sheets) {
                sheetNames.add(sheet.getSheetName());
            }

            // 逐个处理工作表
            for (String sheetName : sheetNames) {
                List<Map<String, Object>> sheetData = new ArrayList<>();

                // 读取当前工作表
                ReadSheet readSheet = EasyExcel.readSheet(sheetName)
                        .registerReadListener(new AnalysisEventListener<Map<Integer, String>>() {
                            private List<String> headerList;

                            @Override
                            public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                                headerList = new ArrayList<>();
                                for (int i = 0; i < headMap.size(); i++) {
                                    headerList.add(headMap.get(i));
                                }
                            }

                            @Override
                            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                                Map<String, Object> rowMap = new HashMap<>();
                                for (int i = 0; i < headerList.size(); i++) {
                                    rowMap.put(headerList.get(i), data.get(i));
                                }
                                sheetData.add(rowMap);
                            }

                            @Override
                            public void doAfterAllAnalysed(AnalysisContext context) {
                                System.out.println("工作表 [" + sheetName + "] 解析完成");
                            }
                        }).build();

                excelReader.read(readSheet);
                allSheetsData.add(sheetData);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return allSheetsData;
    }

}
