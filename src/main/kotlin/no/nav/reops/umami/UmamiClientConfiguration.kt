package no.nav.reops.umami

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

@Configuration
class UmamiClientConfiguration(
    @Value("\${reops.umami.url}") private val umamiUrl: String
) {
    @Bean
    fun umamiClient(): WebClient {
        val connectionProvider = ConnectionProvider.builder("umami")
            .maxConnections(50)
            .pendingAcquireMaxCount(500)
            .pendingAcquireTimeout(Duration.ofSeconds(30))
            .maxIdleTime(Duration.ofSeconds(20))
            .maxLifeTime(Duration.ofSeconds(60))
            .evictInBackground(Duration.ofSeconds(10))
            .build()

        val httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(10))

        return WebClient.builder()
            .baseUrl(umamiUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}