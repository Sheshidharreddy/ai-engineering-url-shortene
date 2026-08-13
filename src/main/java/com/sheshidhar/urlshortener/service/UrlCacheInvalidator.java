package com.sheshidhar.urlshortener.service;

public interface UrlCacheInvalidator {

    void evict(String shortCode);
}
