package com.sheshidhar.urlshortener.url;

import java.util.Optional;

public interface UrlCache {

    Optional<CachedUrl> find(String shortCode);

    void put(UrlMapping mapping);
}
