package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.InvalidDestinationUrlException;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
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

            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
                throw new InvalidDestinationUrlException("url scheme must be http or https");
            }
            URL url = uri.toURL();
            if (url.getHost() == null || url.getHost().isBlank()) {
                throw new InvalidDestinationUrlException("url must include a valid host");
            }
            if (url.getUserInfo() != null) {
                throw new InvalidDestinationUrlException("url must not include user credentials");
            }
            if (url.getPort() == 0 || url.getPort() > 65_535) {
                throw new InvalidDestinationUrlException("url port must be between 1 and 65535");
            }

            return normalizeHost(trimmed, uri, url, scheme);
        } catch (IllegalArgumentException | java.net.MalformedURLException | URISyntaxException exception) {
            throw new InvalidDestinationUrlException("url is not a valid URI");
        }
    }

    private String normalizeHost(String original, URI uri, URL url, String scheme) throws URISyntaxException {
        String host = url.getHost();
        String asciiHost = host.indexOf(':') >= 0
                ? host
                : IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && !asciiHost.startsWith("[")) {
            asciiHost = "[" + asciiHost + "]";
        }

        String authority = asciiHost + (url.getPort() < 0 ? "" : ":" + url.getPort());
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null) {
            throw new InvalidDestinationUrlException("url must include a valid host");
        }

        int authorityStart = original.indexOf("//") + 2;
        int authorityEnd = authorityStart + rawAuthority.length();
        String normalized = scheme
                + original.substring(uri.getScheme().length(), authorityStart)
                + authority
                + original.substring(authorityEnd);
        return new URI(normalized).toASCIIString();
    }
}
