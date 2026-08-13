package com.sheshidhar.urlshortener.url;

public interface UrlCacheInvalidator {

    void evict(String shortCode);
}
