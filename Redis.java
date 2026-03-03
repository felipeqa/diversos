@Bean
RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheWriter cacheWriter = RedisCacheWriter.lockingRedisCacheWriter(
        connectionFactory,
        Duration.ofSeconds(30) // TTL máximo do lock
    );
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(15));
    return new RedisCacheManager(cacheWriter, config);
}
