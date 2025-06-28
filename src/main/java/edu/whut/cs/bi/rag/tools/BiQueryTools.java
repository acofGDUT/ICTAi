package edu.whut.cs.bi.rag.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.whut.cs.bi.rag.service.TokenManager;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.function.Function;

@Configuration
public class BiQueryTools {
    @Value("${bi-location}")
    private String base_Url;
    @Resource
    private RestTemplate restTemplate;
    @Resource
    private TokenManager tokenManager;

    public record BiPropertyRequest(String BuildingName){};

    public record BiProjectRequest(String UserId){
    }
    public record  BiTaskRequest(String ProjectId){}

    @Bean
    @Description("通过桥梁名称拿到桥梁属性树，属性树中包含桥梁结构信息、属性信息。")
    public Function<BiPropertyRequest, String> getBiProperty() {
            return request->{
                String url = base_Url+"/toolCallingApi/building/"+request.BuildingName+"/property";
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
                System.out.println("res:"+res);
                return "查询的属性树 json:"+res;
            };
    }


    @Bean
    @Description("通过userId拿到该人员参与的项目(Project)信息")
    public Function<BiProjectRequest, String> getBiPropject() {
        return request->{
            String url = base_Url+"/toolCallingApi/project/"+request.UserId;
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
            System.out.println("res:"+res);
            return "查询的用户项目信息:"+res;
        };
    }

    @Bean
    @Description("通过工具或对话拿到的projectId(项目Id)拿到任务(task)信息")
    public Function<BiTaskRequest, String> getBiTask() {
        return request->{
            String url = base_Url+"/toolCallingApi/project/"+request.ProjectId+"/task";
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
            System.out.println("res:"+res);
            return "查询的项目的任务信息:"+res;

        };
    }

}
