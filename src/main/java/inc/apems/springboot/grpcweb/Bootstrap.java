package inc.apems.springboot.grpcweb;

import org.apache.catalina.Context;
import org.apache.coyote.AbstractProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Bootstrap {
    public static void main(String[] args) {
        SpringApplication.run(Bootstrap.class, args);
    }

    @Value( "${proxy.backend-address}" )
    private String[] addresses;
    @Value("${proxy.request-timeout}")
    String requestTimeout = "0";

    @Bean
    Channels channels() {
        return new Channels(addresses);
    }

    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        tomcat.addConnectorCustomizers(connector -> {
            ((AbstractProtocol) connector.getProtocolHandler()).setKeepAliveTimeout(Integer.parseInt(requestTimeout) * 1000);
        });
        return tomcat;
    }
}
