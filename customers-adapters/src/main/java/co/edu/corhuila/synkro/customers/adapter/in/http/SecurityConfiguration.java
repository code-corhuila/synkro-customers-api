package co.edu.corhuila.synkro.customers.adapter.in.http;

import co.edu.corhuila.synkro.customers.application.port.in.Caller;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.SecurityFilterChain;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

@Configuration
public class SecurityConfiguration {
    @Bean public JwtDecoder decoder(@Value("${JWT_PUBLIC_KEY:}") String pem,@Value("${JWT_PUBLIC_KEY_FILE:}") String file) {
        try {
            if(!file.isBlank())pem=Files.readString(Path.of(file));
            pem=pem.replace("\\n","\n");
            var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----",""))));
            if(key.getModulus().bitLength()<2048)throw new IllegalArgumentException();
            var decoder=NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),jwt -> {
                try {
                    if(jwt.getExpiresAt()==null||jwt.getIssuedAt()==null||jwt.getId()==null||jwt.getId().isBlank()||jwt.getSubject()==null||jwt.getSubject().isBlank())throw new IllegalArgumentException();
                    for(String field:List.of("roles","permissions")){
                        Object claim=jwt.getClaims().get(field);
                        if(!(claim instanceof List<?> values)||values.stream().anyMatch(v -> !(v instanceof String text)||text.isBlank()))throw new IllegalArgumentException();
                    }
                    List<String> roles=jwt.getClaimAsStringList("roles");if(roles.isEmpty())throw new IllegalArgumentException();
                    if(!roles.contains("SERVICE")&&!UUID.fromString(jwt.getSubject()).toString().equalsIgnoreCase(jwt.getSubject()))throw new IllegalArgumentException();
                    if(jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30))||!jwt.getExpiresAt().isAfter(jwt.getIssuedAt()))throw new IllegalArgumentException();
                    return OAuth2TokenValidatorResult.success();
                }catch(Exception e){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));}
            }));return decoder;
        }catch(Exception e){throw new IllegalStateException("Valid Auth public key is required");}
    }
    public static Caller caller(Jwt jwt) { return new Caller(jwt.getSubject(),Set.copyOf(jwt.getClaimAsStringList("roles")),Set.copyOf(jwt.getClaimAsStringList("permissions"))); }
    @Bean public SecurityFilterChain filterChain(HttpSecurity http,ObjectMapper json) throws Exception {
        http.csrf(c -> c.disable()).httpBasic(c -> c.disable()).formLogin(c -> c.disable())
            .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c -> c.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll().requestMatchers("/health").permitAll()
                .requestMatchers("/api/v1/customers","/api/v1/customers/**").access((auth,ctx) -> {
                    if(!(auth.get() instanceof JwtAuthenticationToken token))return new AuthorizationDecision(false);
                    boolean read="GET".equals(ctx.getRequest().getMethod())&&ctx.getRequest().getRequestURI().matches("/api/v1/customers/[^/]+");
                    try { caller(token.getToken()).authorize(read);return new AuthorizationDecision(true); }
                    catch(co.edu.corhuila.synkro.customers.domain.model.CustomerFailure e){return new AuthorizationDecision(false);}
                }).anyRequest().authenticated())
            .addFilterBefore(new CorrelationFilter(),BearerTokenAuthenticationFilter.class)
            .oauth2ResourceServer(c -> c.jwt(j -> {}).authenticationEntryPoint((r,s,e) -> HttpErrors.write(json,r,s,401,"UNAUTHORIZED"))
                .accessDeniedHandler((r,s,e) -> HttpErrors.write(json,r,s,403,"FORBIDDEN")))
            .exceptionHandling(c -> c.authenticationEntryPoint((r,s,e) -> HttpErrors.write(json,r,s,401,"UNAUTHORIZED"))
                .accessDeniedHandler((r,s,e) -> HttpErrors.write(json,r,s,403,"FORBIDDEN")));
        return http.build();
    }
}
