package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import java.util.ArrayList;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiseaseSummaryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<DiseaseSummaryService> services = new ArrayList<>();

    @AfterEach
    void shutdown() { services.forEach(DiseaseSummaryService::shutdownExecutor); }

    @Test
    void sortsTypesStablyAndAppendsQuantityTotalsWithComponentAndCrackDetails() throws Exception {
        DiseaseSummaryService service = serviceWith(mock(ResponsesApiClient.class));
        String json = """
                [
                  {"id":1,"position":"内翼板","type":"裂缝","crackType":"纵向","biObjectName":"小箱梁","quantity":2},
                  {"id":2,"position":"内翼板","type":"剥落","biObjectName":"小箱梁","quantity":2},
                  {"id":3,"position":"内翼板","type":"裂缝","crackType":"横向","biObjectName":"小箱梁","quantity":1},
                  {"id":4,"position":"内翼板","type":"剥落","biObjectName":"小箱梁","quantity":1},
                  {"id":5,"position":"内翼板","type":"裂缝","crackType":"纵向","biObjectName":"横梁","quantity":3},
                  {"id":6,"position":"底板","type":"剥落","biObjectName":"小箱梁","quantity":9}
                ]
                """;
        var grouped = service.groupInput(json);
        var records = grouped.groups().get(new DiseaseSummaryService.GroupKey("小箱梁", "内翼板"));
        var ids = new ArrayList<Integer>();
        records.forEach(item -> ids.add(item.path("id").asInt()));
        assertEquals(List.of(2, 4, 1, 3), ids);
        assertEquals(List.of("小箱梁", "横梁"), grouped.componentOrder());
        assertEquals(4, records.size());
        var counts = service.buildQuantityCounts(records);
        assertEquals("剥落", counts.get(0).path("type").asText());
        assertEquals(2, counts.get(0).path("recordCount").asInt());
        assertEquals(3, counts.get(0).path("quantityTotal").asInt());
        assertEquals(3, counts.get(1).path("quantityTotal").asInt());
        assertEquals(2, counts.get(1).path("details").size());
        assertEquals(2, counts.get(1).path("details").get(0).path("quantityTotal").asInt());
        assertEquals(1, counts.get(1).path("details").get(1).path("quantityTotal").asInt());
        var crossBeamCounts = service.buildQuantityCounts(
                grouped.groups().get(new DiseaseSummaryService.GroupKey("横梁", "内翼板")));
        assertEquals(3, crossBeamCounts.get(0).path("quantityTotal").asInt());
        assertEquals("横梁", crossBeamCounts.get(0).path("details").get(0).path("biObjectName").asText());
        assertEquals(9, service.buildQuantityCounts(grouped.groups().get(new DiseaseSummaryService.GroupKey("小箱梁", "底板"))).get(0).path("quantityTotal").asInt());
        String message = service.buildGroupMessage(new DiseaseSummaryService.GroupKey("小箱梁", "内翼板"), records);
        String modelJson = message.split("JSON：\\n", 2)[1].split("\\n\\n", 2)[0];
        var modelRecords = objectMapper.readTree(modelJson);
        assertEquals(records.size(), modelRecords.size());
        for (int i = 0; i < records.size(); i++) {
            var expected = (com.fasterxml.jackson.databind.node.ObjectNode) records.get(i).deepCopy();
            expected.remove("id");
            assertEquals(expected, modelRecords.get(i));
        }
        org.junit.jupiter.api.Assertions.assertTrue(message.contains(counts.toString()));
        org.junit.jupiter.api.Assertions.assertTrue(message.indexOf(modelJson) < message.indexOf(counts.toString()));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("数量以 quantityTotal 为准"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("数量为1条或1处时写“长度/面积”"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("biObjectName为“小箱梁”，position为“内翼板”"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("必须以“1）小箱梁：”开头"));
        // 排序不得改变原始记录内容或丢记录。
        assertEquals(objectMapper.readTree(json).get(1), records.get(0));
    }

    @Test
    void rejectsUnknownOrInvalidQuantitiesInsteadOfInventingCounts() {
        DiseaseSummaryService service = serviceWith(mock(ResponsesApiClient.class));
        for (String value : List.of("null", "-1", "1.5", "\"2\"", "9223372036854775808")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.groupInput("[{\"position\":\"支座\",\"quantity\":" + value + "}]"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.groupInput("[{\"position\":\"支座\"}]"));
    }

    @Test
    void groupsByComponentAndPositionAndPreservesFirstSeenOrder() {
        DiseaseSummaryService service = serviceWith(mock(ResponsesApiClient.class));
        String json = """
                [
                  {"id":1,"position":"外翼板","biObjectName":"小箱梁","quantity":1},
                  {"id":2,"position":"内腹板","biObjectName":"小箱梁","quantity":1},
                  {"id":3,"position":"外翼板","biObjectName":"小箱梁","quantity":1}
                ]
                """;

        DiseaseSummaryService.GroupedInput input = service.groupInput(json);

        assertEquals(List.of(new DiseaseSummaryService.GroupKey("小箱梁", "外翼板"),
                new DiseaseSummaryService.GroupKey("小箱梁", "内腹板")), input.groups().keySet().stream().toList());
        assertEquals(2, input.groups().get(new DiseaseSummaryService.GroupKey("小箱梁", "外翼板")).size());
        assertEquals(1, input.groups().get(new DiseaseSummaryService.GroupKey("小箱梁", "内腹板")).size());
        assertEquals(List.of("小箱梁"), input.componentOrder());
    }

    @Test
    void separatesComponentsAtTheSamePositionAndMergesTheirOwnPositions() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5-nano"), eq("system"), anyString()))
                .thenReturn(result("parent", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("parent"), contains("biObjectName为“横隔板”，position为“底板”")))
                .thenReturn(result("cross-bottom", "1）横隔板：底板剥落3处，总面积0.3m²。", 1300, 1024));
        when(client.continueFrom(eq("gpt-5-nano"), eq("parent"), contains("biObjectName为“湿接缝”，position为“底板”")))
                .thenReturn(result("joint-bottom", "1）湿接缝：底板剥落7处，总面积0.7m²。", 1300, 1024));
        when(client.continueFrom(eq("gpt-5-nano"), eq("parent"), contains("biObjectName为“横隔板”，position为“侧面”")))
                .thenReturn(result("cross-side", "1）横隔板：侧面竖向裂缝4条，总长度4m。", 1300, 1024));
        DiseaseSummaryService service = track(new DiseaseSummaryService(objectMapper, client, 86400, 3, "gpt-5-nano", "gpt-5.6-luna", 40, 0));

        String summary = service.summarize("""
                [
                  {"id":1,"biObjectName":"横隔板","position":"底板","type":"剥落","quantity":2},
                  {"id":2,"biObjectName":"湿接缝","position":"底板","type":"剥落","quantity":7},
                  {"id":3,"biObjectName":"横隔板","position":"侧面","type":"竖向裂缝","quantity":4},
                  {"id":4,"biObjectName":"横隔板","position":"底板","type":"剥落","quantity":1}
                ]
                """, "system");

        assertEquals("1）横隔板：底板剥落3处，总面积0.3m²；侧面竖向裂缝4条，总长度4m。\n"
                + "2）湿接缝：底板剥落7处，总面积0.7m²。", summary);
        var messages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).continueFrom(eq("gpt-5-nano"), eq("parent"), messages.capture());
        int totalRecords = 0;
        for (String message : messages.getAllValues()) {
            String[] sections = message.split("JSON：\\n", 2)[1].split("\\n\\n", 2);
            var records = objectMapper.readTree(sections[0]);
            var counts = objectMapper.readTree(sections[1].split("\\n", 3)[1]);
            String component = records.get(0).path("biObjectName").asText();
            String position = records.get(0).path("position").asText();
            for (var record : records) {
                assertEquals(component, record.path("biObjectName").asText());
                assertEquals(position, record.path("position").asText());
            }
            int expectedQuantity = component.equals("湿接缝") ? 7 : position.equals("底板") ? 3 : 4;
            assertEquals(expectedQuantity, counts.get(0).path("quantityTotal").asInt());
            totalRecords += records.size();
        }
        assertEquals(4, totalRecords);
        verify(client).createParent(eq("gpt-5-nano"), eq("system"), anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(client);
    }

    @Test
    void keepsCompositeKeysDistinctAndPreservesBlankFieldCompatibility() {
        DiseaseSummaryService service = serviceWith(mock(ResponsesApiClient.class));
        var input = service.groupInput("""
                [
                  {"biObjectName":" A ","position":"B+C","quantity":1},
                  {"biObjectName":"A+B","position":"C","quantity":1},
                  {"biObjectName":"A","position":"B+C ","quantity":1},
                  {"biObjectName":"A","position":null,"quantity":1},
                  {"biObjectName":"A","position":"未指定位置","quantity":1},
                  {"biObjectName":null,"position":"底板","quantity":1},
                  {"biObjectName":" ","position":"底板","quantity":1}
                ]
                """);

        assertEquals(5, input.groups().size());
        assertEquals(2, input.groups().get(new DiseaseSummaryService.GroupKey("A", "B+C")).size());
        assertEquals(1, input.groups().get(new DiseaseSummaryService.GroupKey("A+B", "C")).size());
        assertEquals(1, input.groups().get(new DiseaseSummaryService.GroupKey("A", "")).size());
        assertEquals(1, input.groups().get(new DiseaseSummaryService.GroupKey("A", "未指定位置")).size());
        assertEquals(2, input.groups().get(new DiseaseSummaryService.GroupKey("", "底板")).size());
        assertEquals(List.of("A", "A+B"), input.componentOrder());
    }

    @Test
    void selectsPositionGroupingOnlyForUpperLoadBearingSections() throws Exception {
        DiseaseSummaryService service = serviceWith(mock(ResponsesApiClient.class));
        String records = """
                [
                  {"biObjectName":"横隔板","position":"底面","type":"裂缝","crackType":"竖向","quantity":2},
                  {"biObjectName":"横隔板","position":"侧面","type":"裂缝","crackType":"竖向","quantity":3},
                  {"biObjectName":"横隔板","position":"侧面","type":"裂缝","crackType":"斜向","quantity":4},
                  {"biObjectName":"湿接缝","position":"底面","type":"裂缝","crackType":"横向","quantity":7}
                ]
                """;
        for (String section : List.of("上部承重构件", "上部承重结构")) {
            var grouped = service.groupInput(sectionRequest(section, records));
            assertEquals(List.of(new DiseaseSummaryService.GroupKey("横隔板", "底面"),
                    new DiseaseSummaryService.GroupKey("横隔板", "侧面"),
                    new DiseaseSummaryService.GroupKey("湿接缝", "底面")),
                    grouped.groups().keySet().stream().toList());
        }
        for (String section : List.of("上部一般构件", "上部一般结构", "支座", "桥墩", "桥台", "墩台基础", "桥面铺装")) {
            var grouped = service.groupInput(sectionRequest(section, records));
            assertEquals(List.of(new DiseaseSummaryService.GroupKey("横隔板", null),
                    new DiseaseSummaryService.GroupKey("湿接缝", null)),
                    grouped.groups().keySet().stream().toList());
            var counts = service.buildQuantityCounts(grouped.groups().get(new DiseaseSummaryService.GroupKey("横隔板", null)));
            assertEquals(9, counts.get(0).path("quantityTotal").asInt());
            assertEquals(2, counts.get(0).path("details").size());
            assertEquals("竖向", counts.get(0).path("details").get(0).path("crackType").asText());
            assertEquals(5, counts.get(0).path("details").get(0).path("quantityTotal").asInt());
            assertEquals(4, counts.get(0).path("details").get(1).path("quantityTotal").asInt());
            assertEquals(7, service.buildQuantityCounts(grouped.groups().get(new DiseaseSummaryService.GroupKey("湿接缝", null)))
                    .get(0).path("quantityTotal").asInt());
        }
        // 部位由请求明确指定，不能按“横隔板”等构件名反推；旧数组维持构件＋位置模式。
        assertEquals(service.groupInput(sectionRequest("上部承重构件", records)).groups(), service.groupInput(records).groups());
    }

    @Test
    void componentOnlyRequestsCombinePositionsBeforeRoutingAndKeepOriginalRecords() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5.6-luna"), eq("system"), anyString()))
                .thenReturn(result("luna-parent", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5.6-luna"), eq("luna-parent"), anyString()))
                .thenReturn(result("luna-child", "1）横隔板：剥落82处，总面积8.2m²。", 1400, 1024));
        DiseaseSummaryService service = singleThreadService(client);
        var records = objectMapper.createArrayNode();
        for (int i = 0; i < 41; i++) {
            records.addObject().put("id", i).put("biObjectName", "横隔板")
                    .put("position", i < 21 ? "底面" : "侧面").put("type", "剥落")
                    .put("description", "剥落2处，总面积0.2m²").put("quantity", 2);
        }
        String request = sectionRequest("上部一般构件", records.toString());
        var originalGroup = service.groupInput(request).groups().values().iterator().next();
        String message = service.buildGroupMessage(new DiseaseSummaryService.GroupKey("横隔板", null), originalGroup);
        assertEquals(records, originalGroup);
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("本轮汇总模式：仅构件"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("不得按position或description中的位置再次拆分"));
        String[] sections = message.split("JSON：\\n", 2)[1].split("\\n\\n", 2);
        var modelRecords = objectMapper.readTree(sections[0]);
        assertEquals(41, modelRecords.size());
        for (var record : modelRecords) {
            org.junit.jupiter.api.Assertions.assertFalse(record.has("position"));
            org.junit.jupiter.api.Assertions.assertFalse(record.has("id"));
            assertEquals("横隔板", record.path("biObjectName").asText());
        }
        var counts = objectMapper.readTree(sections[1].split("\\n", 3)[1]);
        assertEquals(82, counts.get(0).path("quantityTotal").asInt());
        assertEquals("1）横隔板：剥落82处，总面积8.2m²。", service.summarize(request, "system"));
        verify(client).createParent(eq("gpt-5.6-luna"), eq("system"), anyString());
        verify(client).continueFrom(eq("gpt-5.6-luna"), eq("luna-parent"), eq(message));
        org.mockito.Mockito.verifyNoMoreInteractions(client);
    }

    @Test
    void rejectsIncompleteSectionRequests() {
        DiseaseSummaryService service = serviceWith(mock(ResponsesApiClient.class));
        for (String request : List.of("{}", "{\"diseases\":[]}",
                "{\"sectionName\":\" \" ,\"diseases\":[]}",
                "{\"sectionName\":123,\"diseases\":[]}",
                "{\"sectionName\":\"支座\"}",
                "{\"sectionName\":\"支座\",\"diseases\":{}}",
                "{\"sectionName\":\"支座\",\"diseases\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> service.groupInput(request));
        }
    }

    private String sectionRequest(String sectionName, String records) throws Exception {
        var request = objectMapper.createObjectNode();
        request.put("sectionName", sectionName);
        request.set("diseases", objectMapper.readTree(records));
        return request.toString();
    }

    @Test
    void bearingRetriesOnlyInvalidGroupAndAddsMetadataToValidatedBodies() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5-nano"), eq(BearingSummarySupport.SYSTEM_PROMPT), anyString()))
                .thenReturn(result("bearing-parent", "已准备", 1000, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("bearing-parent"), contains("本组位置：内翼板")))
                .thenReturn(result("invalid", "剥落2处，总面积0.2m²；纵向裂缝3.0m，宽度为0.14mm。", 1500, 0))
                .thenReturn(result("valid", "纵向裂缝2条，总长度3m，宽度为0.14mm；剥落2处，总面积0.2m²。", 1500, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("bearing-parent"), contains("本组位置：外腹板")))
                .thenReturn(result("outer", "剥落1处，面积0.1m²。", 1500, 0));
        DiseaseSummaryService service = track(new DiseaseSummaryService(objectMapper, client, 86400, 3, "gpt-5-nano", "gpt-5.6-luna", 40, 0));
        String input = sectionRequest("上部承重构件", """
                [
                  {"biObjectName":"小箱梁","position":"内翼板","type":"剥落","quantity":2,"description":"面积S=0.2×1m²"},
                  {"biObjectName":"小箱梁","position":"外腹板","type":"剥落","quantity":1,"description":"面积S=0.1×1m²"},
                  {"biObjectName":"小箱梁","position":"内翼板","type":"纵向裂缝","quantity":2,"description":"L=3m，W=0.14mm"}
                ]
                """);

        assertEquals("1）小箱梁：内翼板纵向裂缝2条，总长度3m，宽度为0.14mm；剥落2处，总面积0.2m²；外腹板剥落1处，面积0.1m²。",
                service.summarize(input, "general-prompt"));
        var attempts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).continueFrom(eq("gpt-5-nano"), eq("bearing-parent"), attempts.capture());
        assertEquals(2, attempts.getAllValues().stream().filter(message -> message.contains("本组位置：内翼板")).count());
        assertEquals(1, attempts.getAllValues().stream().filter(message -> message.contains("上次输出未通过程序校验")).count());
        verify(client, times(1)).continueFrom(eq("gpt-5-nano"), eq("bearing-parent"), contains("本组位置：外腹板"));
    }

    @Test
    void bearingQualityFailureReturns502AfterThreeAttempts() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(anyString(), anyString(), anyString())).thenReturn(result("parent", "已准备", 1000, 0));
        when(client.continueFrom(anyString(), anyString(), anyString()))
                .thenReturn(result("bad", "纵向裂缝3m。", 1500, 0));
        DiseaseSummaryService service = singleThreadService(client);
        var controller = new edu.whut.cs.bi.rag.controller.DialogController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "diseaseSummaryService", service);
        String input = sectionRequest("上部承重构件",
                "[{\"biObjectName\":\"小箱梁\",\"position\":\"底板\",\"type\":\"纵向裂缝\",\"quantity\":2}]");
        var response = controller.diseaseSummary(input);
        assertEquals(502, response.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertTrue(response.getBody().contains("纵向裂缝2条"));
        verify(client, times(3)).continueFrom(anyString(), anyString(), anyString());
    }

    @Test
    void bearingAndGeneralPromptsReuseIndependentParentsForTheSameModel() throws Exception {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5-nano"), eq("general"), anyString()))
                .thenReturn(result("general-parent", "已准备", 1000, 0));
        when(client.createParent(eq("gpt-5-nano"), eq(BearingSummarySupport.SYSTEM_PROMPT), anyString()))
                .thenReturn(result("bearing-parent", "已准备", 1000, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("general-parent"), anyString()))
                .thenReturn(result("general-result", "1）支座：脱空1处。", 1300, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("bearing-parent"), anyString()))
                .thenReturn(result("bearing-result", "剥落1处。", 1300, 0));
        DiseaseSummaryService service = singleThreadService(client);
        String general = sectionRequest("支座", "[{\"biObjectName\":\"支座\",\"position\":\"支座\",\"type\":\"脱空\",\"quantity\":1}]");
        String bearing = sectionRequest("上部承重结构", "[{\"biObjectName\":\"小箱梁\",\"position\":\"底板\",\"type\":\"剥落\",\"quantity\":1}]");
        assertEquals("1）支座：脱空1处。", service.summarize(general, "general"));
        assertEquals("1）小箱梁：底板剥落1处。", service.summarize(bearing, "general"));
        assertEquals("1）支座：脱空1处。", service.summarize(general, "general"));
        assertEquals("1）小箱梁：底板剥落1处。", service.summarize(bearing, "general"));
        verify(client, times(1)).createParent(eq("gpt-5-nano"), eq("general"), anyString());
        verify(client, times(1)).createParent(eq("gpt-5-nano"), eq(BearingSummarySupport.SYSTEM_PROMPT), anyString());
    }

    @Test
    void createsOneParentAndForksEveryGroupWithoutWarmup() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5-nano"), eq("system"), anyString()))
                .thenReturn(result("parent-id", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("parent-id"), contains("外翼板")))
                .thenReturn(result("outer-id", "1）小箱梁：外翼板剥落2处，总面积0.2m²。", 1600, 1280));
        when(client.continueFrom(eq("gpt-5-nano"), eq("parent-id"), contains("内腹板")))
                .thenReturn(result("inner-id", "1）小箱梁：内腹板剥落1处，面积0.1m²。", 1500, 1280));

        DiseaseSummaryService service = serviceWith(client);
        String summary = service.summarize("""
                [
                  {"position":"外翼板","biObjectName":"小箱梁","quantity":1},
                  {"position":"内腹板","biObjectName":"小箱梁","quantity":1}
                ]
                """, "system");

        assertEquals("1）小箱梁：外翼板剥落2处，总面积0.2m²；内腹板剥落1处，面积0.1m²。", summary);
        verify(client, times(1)).createParent(eq("gpt-5-nano"), eq("system"), anyString());
        verify(client, times(2)).continueFrom(eq("gpt-5-nano"), eq("parent-id"), anyString());
    }

    @Test
    void retriesOneTransientBranchFailure() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5-nano"), eq("system"), anyString()))
                .thenReturn(result("parent-id", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("parent-id"), contains("外翼板")))
                .thenThrow(new ResponsesApiException("EOF", new java.io.IOException("EOF")))
                .thenReturn(result("outer-id", "1）小箱梁：外翼板剥落1处，总面积0.1m²。", 1500, 1280));

        DiseaseSummaryService service = track(new DiseaseSummaryService(objectMapper, client, 86400, 1, "gpt-5-nano", "gpt-5.6-luna", 40, 0));
        String summary = service.summarize(
                "[{\"position\":\"外翼板\",\"biObjectName\":\"小箱梁\",\"quantity\":1}]", "system");

        assertEquals("1）小箱梁：外翼板剥落1处，面积0.1m²。", summary);
        verify(client, times(2)).continueFrom(eq("gpt-5-nano"), eq("parent-id"), contains("外翼板"));
    }

    @Test
    void removesTotalOnlyForSingularDiseaseMeasurements() {
        String summary = "1）小箱梁：纵向裂缝1条，总长度0.5m，宽度为0.1mm；"
                + "剥落1处，总面积0.02m²；横向裂缝11条，总长度3m；破损2处，总面积0.4m²。";

        assertEquals(
                "1）小箱梁：纵向裂缝1条，长度0.5m，宽度为0.1mm；"
                        + "剥落1处，面积0.02m²；横向裂缝11条，总长度3m；破损2处，总面积0.4m²。",
                DiseaseSummaryService.normalizeSingularMeasureWording(summary));
    }

    @Test
    void rejectsNonArrayJson() {
        DiseaseSummaryService service = serviceWith(mock(ResponsesApiClient.class));
        assertThrows(IllegalArgumentException.class, () -> service.groupInput("{\"position\":\"外翼板\"}"));
    }

    @Test
    void routesEachGroupAt40And41AndReusesSeparateParents() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        for (String model : List.of("gpt-5-nano", "gpt-5.6-luna")) {
            when(client.createParent(eq(model), eq("system"), anyString()))
                    .thenReturn(result(model + "-parent", "已准备", 1200, 0));
            when(client.continueFrom(eq(model), eq(model + "-parent"), anyString()))
                    .thenReturn(result(model + "-child", "1）小箱梁：剥落1处。", 1300, 1024));
        }
        DiseaseSummaryService service = serviceWith(client);
        var input = objectMapper.createArrayNode();
        // 总数 81，但仅 41 条的组合分组走 luna；quantity 不参与阈值判断。
        for (int i = 0; i < 40; i++) {
            input.addObject().put("position", "底板").put("biObjectName", "小箱梁").put("quantity", 100);
        }
        for (int i = 0; i < 41; i++) {
            input.addObject().put("position", "底板").put("biObjectName", "横隔板").put("quantity", 1);
        }
        service.summarize(input.toString(), "system");
        service.summarize(input.toString(), "system");

        for (String model : List.of("gpt-5-nano", "gpt-5.6-luna")) {
            verify(client, times(1)).createParent(eq(model), eq("system"), anyString());
        }
        verify(client, times(2)).continueFrom(eq("gpt-5-nano"), eq("gpt-5-nano-parent"), contains("biObjectName为“小箱梁”，position为“底板”"));
        verify(client, times(2)).continueFrom(eq("gpt-5.6-luna"), eq("gpt-5.6-luna-parent"), contains("biObjectName为“横隔板”，position为“底板”"));
        org.mockito.Mockito.verifyNoMoreInteractions(client);
    }

    @Test
    void refreshingLunaParentDoesNotInvalidateNanoParent() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5-nano"), anyString(), anyString()))
                .thenReturn(result("nano-parent", "已准备", 1200, 0));
        when(client.createParent(eq("gpt-5.6-luna"), anyString(), anyString()))
                .thenReturn(result("luna-old", "已准备", 1200, 0))
                .thenReturn(result("luna-new", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("nano-parent"), anyString()))
                .thenReturn(result("nano-child", "1）小箱梁：剥落1处。", 1300, 1024));
        when(client.continueFrom(eq("gpt-5.6-luna"), eq("luna-old"), anyString()))
                .thenThrow(new ResponsesApiException("previous_response_id not found", 404));
        when(client.continueFrom(eq("gpt-5.6-luna"), eq("luna-new"), anyString()))
                .thenReturn(result("luna-child", "1）小箱梁：剥落41处。", 1300, 1024));
        DiseaseSummaryService service = track(new DiseaseSummaryService(objectMapper, client, 86400, 1, "gpt-5-nano", "gpt-5.6-luna", 40, 0));
        String smallInput = "[{\"position\":\"外翼板\",\"biObjectName\":\"小箱梁\",\"quantity\":1}]";
        service.summarize(smallInput, "system");
        var largeInput = objectMapper.createArrayNode();
        for (int i = 0; i < 41; i++) largeInput.addObject().put("position", "内腹板").put("biObjectName", "小箱梁").put("quantity", 1);
        service.summarize(largeInput.toString(), "system");
        service.summarize(smallInput, "system");
        verify(client, times(1)).createParent(eq("gpt-5-nano"), anyString(), anyString());
        verify(client, times(2)).createParent(eq("gpt-5.6-luna"), anyString(), anyString());
        verify(client, times(2)).continueFrom(eq("gpt-5-nano"), eq("nano-parent"), anyString());
        verify(client, times(1)).continueFrom(eq("gpt-5.6-luna"), eq("luna-new"), anyString());
    }

    private DiseaseSummaryService track(DiseaseSummaryService service) {
        services.add(service);
        return service;
    }

    @Test
    void fallsBackAfterExactlyThreeFailedLunaSummariesUsingExistingNanoParent() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(anyString(), anyString(), anyString()))
                .thenAnswer(call -> result(call.getArgument(0) + "-parent", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("gpt-5-nano-parent"), anyString()))
                .thenReturn(result("nano-child", "1）支座：脱空41处。", 1300, 1024));
        when(client.continueFrom(eq("gpt-5.6-luna"), eq("gpt-5.6-luna-parent"), anyString()))
                .thenThrow(new ResponsesApiException("rate_limit_exceeded", 429));
        DiseaseSummaryService service = singleThreadService(client);
        service.summarize(bearingRecords(1), "system");
        String output = service.summarize(bearingRecords(41), "system");

        assertEquals("1）支座：脱空41处。", output);
        verify(client, times(1)).createParent(eq("gpt-5-nano"), anyString(), anyString());
        verify(client, times(1)).createParent(eq("gpt-5.6-luna"), anyString(), anyString());
        var lunarMessages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).continueFrom(eq("gpt-5.6-luna"), eq("gpt-5.6-luna-parent"), lunarMessages.capture());
        var nanoMessages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).continueFrom(eq("gpt-5-nano"), eq("gpt-5-nano-parent"), nanoMessages.capture());
        assertEquals(lunarMessages.getValue(), nanoMessages.getValue());
        assertEquals(1, lunarMessages.getAllValues().stream().distinct().count());
        org.mockito.Mockito.verifyNoMoreInteractions(client);
    }

    @Test
    void fallsBackWhenCreatingLunaParentFailsThreeTimesWithoutWarmingNanoParent() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5.6-luna"), anyString(), anyString()))
                .thenThrow(new ResponsesApiException("rate_limit_exceeded", 429));
        when(client.createParent(eq("gpt-5-nano"), anyString(), anyString()))
                .thenReturn(result("nano-parent", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5-nano"), eq("nano-parent"), contains("JSON：")))
                .thenReturn(result("nano-child", "1）支座：脱空41处。", 1400, 1024));

        assertEquals("1）支座：脱空41处。", serviceWith(client).summarize(bearingRecords(41), "system"));
        var order = org.mockito.Mockito.inOrder(client);
        order.verify(client, times(3)).createParent(eq("gpt-5.6-luna"), anyString(), anyString());
        order.verify(client).createParent(eq("gpt-5-nano"), anyString(), anyString());
        order.verify(client).continueFrom(eq("gpt-5-nano"), eq("nano-parent"), contains("JSON："));
        org.mockito.Mockito.verifyNoMoreInteractions(client);
    }

    @Test
    void thirdLunaAttemptCanSucceedWithoutCallingNano() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5.6-luna"), anyString(), anyString()))
                .thenReturn(result("luna-parent", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5.6-luna"), eq("luna-parent"), anyString()))
                .thenThrow(new ResponsesApiException("busy", 503))
                .thenThrow(new ResponsesApiException("EOF", new java.io.IOException("EOF")))
                .thenReturn(result("luna-child", "1）支座：脱空41处。", 1400, 1024));
        assertEquals("1）支座：脱空41处。", singleThreadService(client).summarize(bearingRecords(41), "system"));
        verify(client).createParent(eq("gpt-5.6-luna"), anyString(), anyString());
        verify(client, times(3)).continueFrom(eq("gpt-5.6-luna"), eq("luna-parent"), anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(client);
    }

    @Test
    void countsParentAndSummaryFailuresTogetherAndDoesNotLoopBackAfterNanoFailure() {
        ResponsesApiClient client = mock(ResponsesApiClient.class);
        when(client.createParent(eq("gpt-5.6-luna"), anyString(), anyString()))
                .thenThrow(new ResponsesApiException("busy", 503))
                .thenReturn(result("luna-parent", "已准备", 1200, 0));
        when(client.continueFrom(eq("gpt-5.6-luna"), eq("luna-parent"), anyString()))
                .thenThrow(new ResponsesApiException("rate_limit_exceeded", 429));
        ResponsesApiException nanoFailure = new ResponsesApiException("nano unavailable", 503);
        when(client.createParent(eq("gpt-5-nano"), anyString(), anyString())).thenThrow(nanoFailure);
        assertEquals(nanoFailure, assertThrows(ResponsesApiException.class,
                () -> singleThreadService(client).summarize(bearingRecords(41), "system")));
        verify(client, times(2)).createParent(eq("gpt-5.6-luna"), anyString(), anyString());
        verify(client, times(2)).continueFrom(eq("gpt-5.6-luna"), eq("luna-parent"), anyString());
        verify(client).createParent(eq("gpt-5-nano"), anyString(), anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(client);
    }

    private DiseaseSummaryService singleThreadService(ResponsesApiClient client) {
        return track(new DiseaseSummaryService(objectMapper, client, 86400, 1,
                "gpt-5-nano", "gpt-5.6-luna", 40, 0));
    }

    private String bearingRecords(int count) {
        var records = objectMapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            records.addObject().put("position", "支座").put("biObjectName", "支座")
                    .put("type", "脱空").put("quantity", 1);
        }
        return records.toString();
    }

    private DiseaseSummaryService serviceWith(ResponsesApiClient client) {
        return track(new DiseaseSummaryService(objectMapper, client, 86400, 2, "gpt-5-nano", "gpt-5.6-luna", 40, 0));
    }

    private ResponsesApiClient.ResponseResult result(
            String id, String output, long inputTokens, long cachedTokens) {
        return new ResponsesApiClient.ResponseResult(id, output, inputTokens, cachedTokens);
    }
}
