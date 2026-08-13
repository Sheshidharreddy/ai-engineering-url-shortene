package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.dto.CachedUrl;
import com.sheshidhar.urlshortener.entity.UrlMapping;

import java.util.Optional;

public interface UrlCache {

    Optional<CachedUrl> find(String shortCode);

    void put(UrlMapping mapping);
}
