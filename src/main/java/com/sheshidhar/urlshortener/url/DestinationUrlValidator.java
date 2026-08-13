package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.InvalidDestinationUrlException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@Component
public class DestinationUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public String validateAndNormalize(String candidate) {
        if (candidate == null) {
            throw new InvalidDestinationUrlException("url is required");
        }

        String trimmed = candidate.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);

            if (!ALLOWED_SCHEMES.contains(scheme)) {
                throw new InvalidDestinationUrlException("url scheme must be http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new InvalidDestinationUrlException("url must include a valid host");
            }
            if (uri.getRawUserInfo() != null) {
                throw new InvalidDestinationUrlException("url must not include user credentials");
            }
            if (uri.getPort() > 65_535) {
                throw new InvalidDestinationUrlException("url port must be between 1 and 65535");
            }

            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw new InvalidDestinationUrlException("url is not a valid URI");
        }
    }
}
