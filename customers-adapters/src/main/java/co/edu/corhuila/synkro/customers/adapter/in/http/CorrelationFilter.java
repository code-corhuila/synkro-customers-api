package co.edu.corhuila.synkro.customers.adapter.in.http;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.UUID;

public final class CorrelationFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
        String id=r.getHeader("X-Correlation-Id");if(id==null)id=UUID.randomUUID().toString();
        r.setAttribute("correlationId",id);s.setHeader("X-Correlation-Id",id);MDC.put("correlationId",id);
        try { chain.doFilter(r,s); } finally { MDC.remove("correlationId"); }
    }
}
