package no.nav.reops.umami

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class UmamiClientConfiguration(
    @Value("\${reops.umami.url}") private val umamiUrl: String
) {
    @Bean
    fun umamiClient(): RestClient = RestClient.builder().baseUrl(umamiUrl).build()
}