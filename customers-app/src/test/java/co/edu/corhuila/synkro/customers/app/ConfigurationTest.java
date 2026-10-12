package co.edu.corhuila.synkro.customers.app;

import co.edu.corhuila.synkro.customers.adapter.in.http.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path directory;
    @Test void boundedRestrictedDatabaseConfiguration(){
        var c=new CustomersComposition();
        for(String url:new String[]{"jdbc:postgresql://db:5432/synkro","jdbc:postgresql://db:5432/synkro?currentSchema=customers_schema&sslmode=require&connectTimeout=2&loginTimeout=3&socketTimeout=5"})try(var ds=c.datasource(url,"customers_app","synthetic", "")){assertThat(ds.getMaximumPoolSize()).isEqualTo(10);assertThat(ds.getConnectionTimeout()).isEqualTo(5000);}
        for(String query:new String[]{"currentSchema=auth_schema","user=postgres","socketTimeout=0","loginTimeout=60","connectTimeout=abc","socketTimeout=1&socketTimeout=2","sslmode=unknown","currentSchema=customers_schema&"})assertThatThrownBy(()->c.datasource("jdbc:postgresql://db:5432/synkro?"+query,"customers_app","synthetic", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->c.datasource("jdbc:postgresql://db:5432/synkro","postgres","synthetic", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->c.datasource("jdbc:postgresql://password@db:5432/synkro","customers_app","synthetic", "")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void secretFileWinsAndInvalidFileFails()throws Exception{
        var c=new CustomersComposition();var file=directory.resolve("secret");Files.writeString(file,"synthetic with spaces \n");
        try(var ds=c.datasource("jdbc:postgresql://db:5432/synkro","customers_app","ignored",file.toString())){assertThat(ds.getPassword()).isEqualTo("synthetic with spaces ");}
        Files.writeString(file,"");assertThatThrownBy(()->c.datasource("jdbc:postgresql://db:5432/synkro","customers_app","ignored",file.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->c.datasource("jdbc:postgresql://db:5432/synkro","customers_app","ignored",directory.resolve("missing").toString())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void authPublicKeyFileWinsAndInvalidKeyFails()throws Exception{
        var c=new SecurityConfiguration();var file=directory.resolve("public.pem");String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(LifecycleHttpTest.KEYS.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----";
        Files.writeString(file,pem);assertThat(c.decoder("invalid",file.toString())).isNotNull();assertThat(c.decoder(pem.replace("\n","\\n"),"")).isNotNull();
        Files.writeString(file,"");assertThatThrownBy(()->c.decoder(pem,file.toString())).isInstanceOf(IllegalStateException.class).hasMessage("Valid Auth public key is required");
    }
}
