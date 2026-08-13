package com.sheshidhar.urlshortener.url;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RedirectEventRepository extends JpaRepository<RedirectEvent, Long> {

    long countByShortCode(String shortCode);
}
