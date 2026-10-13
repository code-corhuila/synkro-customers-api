package co.edu.corhuila.synkro.customers.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;

public final class HttpErrors {
    private HttpErrors() {}
    public static Map<String,Object> body(HttpServletRequest r,String code,String message,List<Map<String,String>> details) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("error",code);result.put("message",message);
        if(!details.isEmpty())result.put("details",details);result.put("traceId",r.getAttribute("correlationId"));return result;
    }
    public static void write(ObjectMapper json,HttpServletRequest r,HttpServletResponse s,int status,String code) throws IOException {
        s.setStatus(status);s.setContentType("application/json");s.setCharacterEncoding("UTF-8");
        json.writeValue(s.getOutputStream(),body(r,code,status==401?"Missing or invalid authentication token":"Operation is not authorized",List.of()));
    }
}
