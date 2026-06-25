package org.example.ailearning.common;

import lombok.Data;
@Data
public class ApiResponse {
    private  String code;
    private  String message;
    private  String data;

    static ApiResponse success(String data){
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setCode("200");
        apiResponse.setData(data);
        return apiResponse;
    }

    static ApiResponse error(String message){
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setCode("500");
        apiResponse.setMessage(message);
        return apiResponse;
    }

}
