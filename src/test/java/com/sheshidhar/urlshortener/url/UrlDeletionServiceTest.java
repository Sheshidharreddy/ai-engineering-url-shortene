package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.CacheInvalidationException;
import com.sheshidhar.urlshortener.common.error.InvalidShortCodeException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class UrlDeletionServiceTest {

    private final UrlCacheInvalidator cacheInvalidator = mock(UrlCacheInvalidator.class);
    private final UrlDeletionWriter deletionWriter = mock(UrlDeletionWriter.class);
    private final UrlDeletionService service = new UrlDeletionService(
            new ShortCodeValidator(),
            cacheInvalidator,
            deletionWriter
    );

    @Test
    void evictsBeforeAndAfterDatabaseDeletion() {
        assertThatCode(() -> service.delete("product1")).doesNotThrowAnyException();

        InOrder order = inOrder(cacheInvalidator, deletionWriter);
        order.verify(cacheInvalidator).evict("product1");
        order.verify(deletionWriter).delete("product1");
        order.verify(cacheInvalidator).evict("product1");
    }

    @Test
    void abortsDatabaseDeletionWhenInitialEvictionFails() {
        doThrow(new CacheInvalidationException(new IllegalStateException("offline")))
                .when(cacheInvalidator).evict("product1");

        assertThatThrownBy(() -> service.delete("product1"))
                .isInstanceOf(CacheInvalidationException.class);
        verifyNoInteractions(deletionWriter);
    }

    @Test
    void reportsPostCommitEvictionFailureSoARetryCanRepairIt() {
        doNothing()
                .doThrow(new CacheInvalidationException(new IllegalStateException("offline")))
                .when(cacheInvalidator).evict("product1");

        assertThatThrownBy(() -> service.delete("product1"))
                .isInstanceOf(CacheInvalidationException.class);

        InOrder order = inOrder(cacheInvalidator, deletionWriter);
        order.verify(cacheInvalidator).evict("product1");
        order.verify(deletionWriter).delete("product1");
        order.verify(cacheInvalidator).evict("product1");
    }

    @Test
    void validatesBeforeAccessingInfrastructure() {
        assertThatThrownBy(() -> service.delete("bad!"))
                .isInstanceOf(InvalidShortCodeException.class);
        verifyNoInteractions(cacheInvalidator, deletionWriter);
    }
}
