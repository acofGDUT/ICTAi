package edu.whut.cs.bi.rag.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.whut.cs.bi.rag.service.TokenManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@Slf4j
public class BiQueryTools {
    @Value("${bi-location}")
    private String base_Url;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private TokenManager tokenManager;



    @Tool(description = "通过桥梁名称拿到桥梁的重要基本信息，其中包括：行政识别数据（如路线名称、路线等级、设计荷载、桥位桩号、功能类型、监理单位 等）;桥梁技术指标（如桥梁全长、桥面总宽、车道宽度、护栏或防撞墙高度、桥面标准净空、历史洪水位、设计洪水频率及其水位 等）;结构信息;档案资料;检测评定历史;养护处治记录;其他（如桥梁的正面和总体照片）。")
    public String getBiProperty(@ToolParam(description = "需要查询的桥梁名称")String BuildingName) {

            String url = base_Url+"/toolCallingApi/building/"+BuildingName+"/property";
            HttpHeaders headers = new HttpHeaders();
            try {
                headers.set("Authorization",tokenManager.getToken());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            headers.set("Accept", "*/*");
            headers.set("Accept-Encoding", "gzip, deflate, br");
            headers.set("Connection", "keep-alive");
            HttpEntity entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String res = response.getBody();
            log.info("查询的桥梁所有基本属性的 json文本:"+res);
            return "查询的桥梁所有基本属性的 json文本:"+res;
        }


    @Tool(description = "通过userId拿到该人员参与的项目(Project)信息")
    public String getBiPropject(@ToolParam(description = "需要查询的用户Id")String UserId) {
            String url = base_Url+"/toolCallingApi/project/"+UserId;
            HttpHeaders headers = new HttpHeaders();
            try {
                headers.set("Authorization",tokenManager.getToken());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            headers.set("Accept", "*/*");
            headers.set("Accept-Encoding", "gzip, deflate, br");
            headers.set("Connection", "keep-alive");
            HttpEntity entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String res = response.getBody();
            log.info("查询的用户项目json文本:"+res);
            return "查询的用户项目json文本:"+res;
        }

    @Tool(description = "使用本工具前，请务必确保得到了projectId。通过拿到的projectId(项目Id)查询任务(task)信息，注意，如果用户参与了项目A，那么项目A中的所有任务该用户都需要参与。")
    public String getBiTask(@ToolParam(description = "需要查询的projectId")String ProjectId) {

            String url = base_Url+"/toolCallingApi/project/"+ProjectId+"/task";
            HttpHeaders headers = new HttpHeaders();
            try {
                headers.set("Authorization",tokenManager.getToken());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            headers.set("Accept", "*/*");
            headers.set("Accept-Encoding", "gzip, deflate, br");
            headers.set("Connection", "keep-alive");
            HttpEntity entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String res = response.getBody();
            log.info("查询的项目任务json文本:"+res);
            return "查询的项目的任务json文本:"+res;
    }

    @Tool(description = "通过桥梁名称查询桥梁列表，列表中的桥梁对象存放了桥梁Id等信息。")
    public String getBiBuildings(@ToolParam(description = "需要查询的桥梁名称") String BuildingName) {
            String url = base_Url+"/toolCallingApi/building/"+BuildingName;
            HttpHeaders headers = new HttpHeaders();
            try {
                headers.set("Authorization",tokenManager.getToken());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            headers.set("Accept", "*/*");
            headers.set("Accept-Encoding", "gzip, deflate, br");
            headers.set("Connection", "keep-alive");
            HttpEntity entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String res = response.getBody();
            log.info("查询的桥梁列表json文本为:"+res);
            return "查询的桥梁列表json文本为:"+res;
    }

    @Tool(description = "通过桥梁Id和病害年份查看该年桥梁的病害信息")
    public String getBiDisease(@ToolParam(description = "需要查询的桥梁Id")Long BuildingId,@ToolParam(description = "需要查询的年份")Integer year) {
            String url = base_Url+"/toolCallingApi/building/"+BuildingId+"/disease/"+"?year="+year;
            HttpHeaders headers = new HttpHeaders();
            try {
                headers.set("Authorization",tokenManager.getToken());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            headers.set("Accept", "*/*");
            headers.set("Accept-Encoding", "gzip, deflate, br");
            headers.set("Connection", "keep-alive");
            HttpEntity entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String res = response.getBody();
            log.info("查询的桥梁在"+year+"年的病害信息的json文本为:"+res);
            return "查询的桥梁在"+year+"年的病害信息的json文本为:"+res;
    }


    //
//    public record BiPropertyRequest(String BuildingName){};
//
//    public record BiProjectRequest(String UserId){
//    }
//    public record  BiTaskRequest(String ProjectId){}
//    public record BiBuildingRequest(String BuildingName){};
//    public record BiDiseaseRequest(Long BuildingId,Integer year){};

//    @Bean
//    @Description("通过桥梁名称拿到桥梁的重要基本信息，其中包括：行政识别数据（如路线名称、路线等级、设计荷载、桥位桩号、功能类型、监理单位 等）;桥梁技术指标（如桥梁全长、桥面总宽、车道宽度、护栏或防撞墙高度、桥面标准净空、历史洪水位、设计洪水频率及其水位 等）;结构信息;档案资料;检测评定历史;养护处治记录;其他（如桥梁的正面和总体照片）。")
//    public Function<BiPropertyRequest, String> getBiProperty() {
//            return request->{
//                String url = base_Url+"/toolCallingApi/building/"+request.BuildingName+"/property";
//                HttpHeaders headers = new HttpHeaders();
//                try {
//                    headers.set("Authorization",tokenManager.getToken());
//                } catch (JsonProcessingException e) {
//                    throw new RuntimeException(e);
//                }
//                headers.set("Accept", "*/*");
//                headers.set("Accept-Encoding", "gzip, deflate, br");
//                headers.set("Connection", "keep-alive");
//                HttpEntity entity = new HttpEntity<>(headers);
//                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
//                String res = response.getBody();
////                System.out.println("查询的桥梁所有基本属性的 json文本:"+res);
//                return "查询的桥梁所有基本属性的 json文本:"+res;
//            };
//    }
//
//
//    @Bean
//    @Description("通过userId拿到该人员参与的项目(Project)信息")
//    public Function<BiProjectRequest, String> getBiPropject() {
//        return request->{
//            String url = base_Url+"/toolCallingApi/project/"+request.UserId;
//            HttpHeaders headers = new HttpHeaders();
//            try {
//                headers.set("Authorization",tokenManager.getToken());
//            } catch (JsonProcessingException e) {
//                throw new RuntimeException(e);
//            }
//            headers.set("Accept", "*/*");
//            headers.set("Accept-Encoding", "gzip, deflate, br");
//            headers.set("Connection", "keep-alive");
//            HttpEntity entity = new HttpEntity<>(headers);
//            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
//            String res = response.getBody();
////            System.out.println("查询的用户项目json文本:"+res);
//            return "查询的用户项目json文本:"+res;
//        };
//    }
//
//    @Bean
//    @Description("使用本工具前，请务必确保得到了projectId。通过拿到的projectId(项目Id)查询任务(task)信息，注意，如果用户参与了项目A，那么项目A中的所有任务该用户都需要参与。")
//    public Function<BiTaskRequest, String> getBiTask() {
//        return request->{
//            String url = base_Url+"/toolCallingApi/project/"+request.ProjectId+"/task";
//            HttpHeaders headers = new HttpHeaders();
//            try {
//                headers.set("Authorization",tokenManager.getToken());
//            } catch (JsonProcessingException e) {
//                throw new RuntimeException(e);
//            }
//            headers.set("Accept", "*/*");
//            headers.set("Accept-Encoding", "gzip, deflate, br");
//            headers.set("Connection", "keep-alive");
//            HttpEntity entity = new HttpEntity<>(headers);
//            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
//            String res = response.getBody();
////            System.out.println("查询的项目的任务json文本:"+res);
//            return "查询的项目的任务json文本:"+res;
//
//        };
//    }
//
//    @Bean
//    @Description("通过桥梁名称查询桥梁列表，列表中的桥梁对象存放了桥梁Id等信息。")
//    public Function<BiBuildingRequest, String> getBiBuildings() {
//        return request->{
//            String url = base_Url+"/toolCallingApi/building/"+request.BuildingName;
//            HttpHeaders headers = new HttpHeaders();
//            try {
//                headers.set("Authorization",tokenManager.getToken());
//            } catch (JsonProcessingException e) {
//                throw new RuntimeException(e);
//            }
//            headers.set("Accept", "*/*");
//            headers.set("Accept-Encoding", "gzip, deflate, br");
//            headers.set("Connection", "keep-alive");
//            HttpEntity entity = new HttpEntity<>(headers);
//            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
//            String res = response.getBody();
//            System.out.println("查询的桥梁列表json文本为:"+res);
//            return "查询的桥梁列表json文本为:"+res;
//        };
//    }
//
//    @Bean
//    @Description("通过桥梁Id和病害年份查看该年桥梁的病害信息")
//    public Function<BiDiseaseRequest, String> getBiDisease() {
//        return request->{
//            String url = base_Url+"/toolCallingApi/building/"+request.BuildingId+"/disease/"+"?year="+request.year;
//            HttpHeaders headers = new HttpHeaders();
//            try {
//                headers.set("Authorization",tokenManager.getToken());
//            } catch (JsonProcessingException e) {
//                throw new RuntimeException(e);
//            }
//            headers.set("Accept", "*/*");
//            headers.set("Accept-Encoding", "gzip, deflate, br");
//            headers.set("Connection", "keep-alive");
//            HttpEntity entity = new HttpEntity<>(headers);
//            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
//            String res = response.getBody();
//            System.out.println("查询的桥梁在"+request.year+"年的病害信息的json文本为:"+res);
//            return "查询的桥梁在"+request.year+"年的病害信息的json文本为:"+res;
//        };
//    }
}
