package edu.whut.cs.bi.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearingSummarySupportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mergesGenericAndExplicitDirectionalCracksAndKeepsAreaUnits() {
        var categories = BearingSummarySupport.categories(records(
                disease("剥落", "", 2, "S=0.3m²"),
                disease("裂缝", "纵向", 2, "L=2m，W=0.1mm"),
                disease("线状裂缝", "纵向", 3, "L1=3m"),
                disease("纵向裂缝", "", 1, "L_1=1m"),
                disease("裂缝", "横向", 4, "L=4m"),
                disease("网状裂缝", "", 3, "S=0.5m²，宽度0.1mm"),
                disease("蜂窝", "", 7, "")));

        assertEquals(List.of("纵向裂缝", "横向裂缝", "剥落", "网状裂缝", "蜂窝"),
                categories.stream().map(BearingSummarySupport.Category::name).toList());
        assertEquals(new BearingSummarySupport.Category("纵向裂缝", 6, "条", true, false, true), categories.get(0));
        assertEquals("处", categories.get(3).unit());
        assertEquals("处", categories.get(4).unit());
        assertEquals("纵向裂缝6条；必需尺寸：长度、宽度\n横向裂缝4条；必需尺寸：长度\n"
                        + "剥落2处；必需尺寸：面积\n网状裂缝3处；必需尺寸：面积、宽度\n蜂窝7处；必需尺寸：无",
                BearingSummarySupport.checklist(categories));
    }

    @Test
    void detectsEachExplicitLengthAreaAndWidthNotation() {
        for (String description : List.of("L=1.2m", "L1＝2m", "L_1:3m", "总长度为1.2m", "长度：3m")) {
            assertEquals(List.of("长度"), categoryFor(description).measurements(), description);
        }
        for (String description : List.of("S=0.1m²", "S_1＝0.12m²，S_2=0.3m²", "S1:2m²", "面积为0.2m²", "总面积S_1=0.3m²")) {
            assertEquals(List.of("面积"), categoryFor(description).measurements(), description);
        }
        for (String description : List.of("W=0.1mm", "W_1＝0.2mm", "宽度0.12mm", "裂缝宽度为0.2mm", "宽度介于0.1～0.2mm")) {
            assertEquals(List.of("宽度"), categoryFor(description).measurements(), description);
        }
    }

    @Test
    void doesNotMistakeLocationNumbersForDiseaseMeasurements() {
        for (String description : List.of(
                "第1跨2号梁，距梁端2.3m，距腹板0.5m，K23+456处剥落1处",
                "距小桩号端1.2m，距内腹板0.8m，距翼板边缘0.3m",
                "上游侧，跨号3，构件编号W1，桩号S123+456")) {
            assertEquals(List.of(), categoryFor(description).measurements(), description);
        }
        assertEquals(List.of("长度", "宽度"),
                categoryFor("距梁端2.3m、距腹板0.5m，L_1=0.8m，W=0.12mm").measurements());
    }

    @Test
    void rejectsMissingTypeAndQuantityOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> BearingSummarySupport.categories(records(disease(" ", "", 1, ""))));
        assertThrows(ArithmeticException.class, () -> BearingSummarySupport.categories(records(
                disease("剥落", "", Long.MAX_VALUE, ""), disease("剥落", "", 1, ""))));
    }

    @Test
    void acceptsCompletePureBodyAndRestoresChecklistOrder() {
        var validation = BearingSummarySupport.validate(
                "剥落2处，总面积0.3m²；纵向裂缝2条，总长度3m，宽度介于0.1～0.2mm。", commonCategories());

        assertTrue(validation.valid(), validation.errors().toString());
        assertEquals("纵向裂缝2条，总长度3m，宽度介于0.1～0.2mm；剥落2处，总面积0.3m²", validation.body());
    }

    @Test
    void joinsPureMeasurementFragmentsSplitBySemicolons() {
        var validation = BearingSummarySupport.validate(
                "纵向裂缝2条；总长度3m，宽度为0.1mm；剥落2处；总面积0.3m²", commonCategories());

        assertTrue(validation.valid(), validation.errors().toString());
        assertEquals(validBody(), validation.body());
        var prose = BearingSummarySupport.validate(
                "纵向裂缝2条；总长度3m，宽度为0.1mm；剥落2处，总面积0.3m²，原因是施工不当",
                commonCategories());
        assertTrue(prose.valid(), prose.errors().toString());
        assertEquals(validBody(), prose.body());
        assertError("纵向裂缝2条，总长度3m，宽度为0.1mm，剥落99处；剥落2处，总面积0.3m²", "含尺寸之外的额外正文");
    }

    @Test
    void normalizesRealModelAreaLabelsAndDropsPlainExplanatoryText() {
        var categories = BearingSummarySupport.categories(records(disease("剥落", "", 1, "S=0.35×0.20m²")));

        var split = BearingSummarySupport.validate("剥落1处；长度/面积0.07m²", categories);
        assertTrue(split.valid(), split.errors().toString());
        assertEquals("剥落1处，面积0.07m²", split.body());

        var prose = BearingSummarySupport.validate("剥落1处，面积为0.0575平方米，位于构件表面", categories);
        assertTrue(prose.valid(), prose.errors().toString());
        assertEquals("剥落1处，面积0.0575m²", prose.body());
    }

    @Test
    void stillRejectsUnknownNumericContentWhileCleaningProse() {
        var categories = BearingSummarySupport.categories(records(disease("剥落", "", 1, "S=0.07m²")));
        var result = BearingSummarySupport.validate("剥落1处，面积0.07m²，距梁端1.5m", categories);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("尺寸之外的额外正文")), result.errors().toString());
    }

    @Test
    void rejectsMissingOrWrongQuantityAndWrongUnit() {
        for (String clause : List.of(
                "纵向裂缝总长度3m，宽度为0.1mm",
                "纵向裂缝3.0m，宽度为0.1mm",
                "纵向裂缝1条，总长度3m，宽度为0.1mm",
                "纵向裂缝2处，总长度3m，宽度为0.1mm")) {
            var result = BearingSummarySupport.validate(clause + "；剥落2处，总面积0.3m²", commonCategories());
            assertFalse(result.valid(), clause);
            assertEquals("", result.body(), "不应让不合格正文进入报告");
        }
        var mesh = BearingSummarySupport.categories(records(disease("网状裂缝", "", 3, "S=0.5m²")));
        assertFalse(BearingSummarySupport.validate("网状裂缝3条，总面积0.5m²", mesh).valid());
        assertTrue(BearingSummarySupport.validate("网状裂缝3处，总面积0.5m²", mesh).valid());
    }

    @Test
    void rejectsMissingDuplicateUnknownCategoriesAndModelHeadings() {
        assertError("纵向裂缝2条，总长度3m，宽度为0.1mm", "遗漏类别：剥落");
        assertError(validBody() + "；剥落2处，总面积0.3m²", "剥落重复输出");
        assertError(validBody() + "；空洞1处，面积0.1m²", "含未知类别或标题");
        assertError("1）小箱梁：" + validBody(), "含未知类别或标题");
        assertError("内翼板" + validBody(), "含未知类别或标题");
        assertError("本轮病害小结：\n" + validBody(), "含未知类别或标题");
        assertError(null, "遗漏类别：纵向裂缝");
    }

    @Test
    void requiresMeasurementsInTheirOwnCategoryInsteadOfAnywhereInBody() {
        assertError("纵向裂缝2条，宽度为0.1mm；剥落2处，总面积0.3m²，总长度3m", "纵向裂缝缺少规范的长度");
        assertError("纵向裂缝2条，总长度3m；剥落2处，总面积0.3m²，宽度为0.1mm", "纵向裂缝缺少规范的宽度");
        assertError("纵向裂缝2条，总长度3m，宽度为0.1mm，总面积0.3m²；剥落2处", "剥落缺少规范的面积");
    }

    @Test
    void doesNotAcceptAreaOrMillimetersAsRequiredLength() {
        for (String mistakenLength : List.of("3m²", "3mm", "3m2", "3m^2")) {
            assertError("纵向裂缝2条，总长度" + mistakenLength + "，宽度为0.1mm；剥落2处，总面积0.3m²",
                    "纵向裂缝缺少规范的长度");
        }
    }

    @Test
    void rejectsMaximumMinimumAndTotalWidthInsteadOfAcceptingTheirWidthSubstring() {
        for (String width : List.of("最大宽度0.2mm", "最小宽度为0.1mm", "总宽度为0.3mm")) {
            var result = BearingSummarySupport.validate(
                    "纵向裂缝2条，总长度3m，" + width + "；剥落2处，总面积0.3m²", commonCategories());
            assertFalse(result.valid(), width);
            assertEquals("", result.body(), "不应将不合规宽度措辞微处理后写入报告");
            assertTrue(result.errors().stream().anyMatch(error -> error.contains("宽度")), result.errors().toString());
        }
        assertTrue(BearingSummarySupport.validate(validBody(), commonCategories()).valid());
        assertTrue(BearingSummarySupport.validate(
                "纵向裂缝2条，总长度3m，宽度介于0.1～0.2mm；剥落2处，总面积0.3m²", commonCategories()).valid());
    }

    @Test
    void normalizesSingleAndMultipleQuantityWordingAndBareWidth() {
        var categories = BearingSummarySupport.categories(records(
                disease("裂缝", "纵向", 1, "L=3m，W=0.1mm"),
                disease("剥落", "", 1, "S=0.3m²")));
        var single = BearingSummarySupport.validate(
                "纵向裂缝1条，总长度3m，宽度0.1mm；剥落1处，总面积0.3m²。", categories);
        assertTrue(single.valid(), single.errors().toString());
        assertEquals("纵向裂缝1条，长度3m，宽度为0.1mm；剥落1处，面积0.3m²", single.body());

        var multiple = BearingSummarySupport.validate(
                "纵向裂缝2条，长度3m，宽度为0.1mm；剥落2处，面积0.3m²", commonCategories());
        assertTrue(multiple.valid(), multiple.errors().toString());
        assertEquals(validBody(), multiple.body());
    }

    @Test
    void matchesLongerCategoryNameWithoutConsumingAnotherCategoryPrefix() {
        var categories = BearingSummarySupport.categories(records(
                disease("纵向裂缝", "", 2, "L=3m"),
                disease("纵向裂缝伴析白", "", 1, "L=1m")));
        var result = BearingSummarySupport.validate("纵向裂缝伴析白1条，长度1m；纵向裂缝2条，总长度3m", categories);
        assertTrue(result.valid(), result.errors().toString());
        assertEquals("纵向裂缝2条，总长度3m；纵向裂缝伴析白1条，长度1m", result.body());
    }

    @Test
    void mergesUsingRequestComponentAndPositionMetadataWithoutModelTitles() {
        var keys = List.of(new DiseaseSummaryService.GroupKey("小箱梁", "外腹板"),
                new DiseaseSummaryService.GroupKey("横梁", "底板"),
                new DiseaseSummaryService.GroupKey("小箱梁", "内翼板"));
        var bodies = List.of("纵向裂缝2条，总长度3m", "剥落1处，面积0.1m²", "剥落2处，总面积0.3m²");

        assertEquals("1）横梁：底板剥落1处，面积0.1m²。\n"
                        + "2）小箱梁：外腹板纵向裂缝2条，总长度3m；内翼板剥落2处，总面积0.3m²。",
                BearingSummarySupport.merge(keys, bodies, List.of("横梁", "小箱梁", "无病害构件")));
    }

    @Test
    void omitsAbsentPositionsAndDoesNotRepeatPositionEqualToComponent() {
        var keys = List.of(new DiseaseSummaryService.GroupKey("小箱梁", null),
                new DiseaseSummaryService.GroupKey("横梁", "横梁"),
                new DiseaseSummaryService.GroupKey("新增构件", ""));
        assertEquals("1）小箱梁：剥落1处。\n2）横梁：剥落2处。\n3）新增构件：剥落3处。",
                BearingSummarySupport.merge(keys, List.of("剥落1处", "剥落2处", "剥落3处"), List.of("小箱梁", "横梁")));
    }

    private List<BearingSummarySupport.Category> commonCategories() {
        return BearingSummarySupport.categories(records(
                disease("裂缝", "纵向", 2, "L=3m，W=0.1mm"), disease("剥落", "", 2, "S=0.3m²")));
    }

    private BearingSummarySupport.Category categoryFor(String description) {
        return BearingSummarySupport.categories(records(disease("裂缝", "纵向", 1, description))).get(0);
    }

    private void assertError(String body, String expectedError) {
        var result = BearingSummarySupport.validate(body, commonCategories());
        assertFalse(result.valid(), String.valueOf(body));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains(expectedError)), result.errors().toString());
    }

    private String validBody() {
        return "纵向裂缝2条，总长度3m，宽度为0.1mm；剥落2处，总面积0.3m²";
    }

    private ObjectNode disease(String type, String direction, long quantity, String description) {
        return mapper.createObjectNode().put("type", type).put("crackType", direction)
                .put("quantity", quantity).put("description", description);
    }

    private ArrayNode records(ObjectNode... records) {
        var result = mapper.createArrayNode();
        for (var record : records) result.add(record);
        return result;
    }
}
