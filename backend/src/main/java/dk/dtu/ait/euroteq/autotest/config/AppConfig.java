package dk.dtu.ait.euroteq.autotest.config;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.util.concurrent.Executor;

@Configuration
public class AppConfig {

    @Value("${euroteq.test.thread-pool-size:10}")
    private int threadPoolSize;

    @Bean(name = "testExecutor")
    public Executor testExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threadPoolSize);
        executor.setMaxPoolSize(threadPoolSize * 2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("test-exec-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "noRedirectRestTemplate")
    public RestTemplate noRedirectRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    @Bean(name = "connectivityRestTemplate")
    public RestTemplate connectivityRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    @Bean(name = "standardRestTemplate")
    public RestTemplate standardRestTemplate() {
        // HttpComponentsClientHttpRequestFactory supports PATCH; SimpleClientHttpRequestFactory does not.
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setConnectionManager(
                                org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                                        .setMaxConnTotal(50)
                                        .setMaxConnPerRoute(20)
                                        .build()
                        )
                        .build()
        );
        factory.setConnectTimeout(10000);
        factory.setConnectionRequestTimeout(10000);
        return new RestTemplate(factory);
    }
}
