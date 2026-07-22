package io.tolgee.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate

class RedisWebsocketEventPublisher(
  private val redisTemplate: StringRedisTemplate,
  private val objectMapper: ObjectMapper,
) : WebsocketEventPublisher {
  override operator fun invoke(
    destination: String,
    message: WebsocketEvent,
  ) {
    val messageString = objectMapper.writeValueAsString(RedisWebsocketEventWrapper(destination, message))
    redisTemplate.convertAndSend(
      "websocket",
      messageString,
    )
  }
}
