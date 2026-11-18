package com.iptv.tv;

import static org.junit.Assert.*;

import org.junit.Test;

public final class CatalogueRepositoryTest {
    @Test public void evaluatesRefreshIntervalsWithoutWaitingForAClock() {
        long previous = 1000;
        assertFalse(CatalogueRepository.refreshDue(0, 0, 1000));
        assertTrue(CatalogueRepository.refreshDue(1, 0, 1000));
        assertFalse(CatalogueRepository.refreshDue(1, previous, previous + 3_599_999));
        assertTrue(CatalogueRepository.refreshDue(1, previous, previous + 3_600_000));
        assertFalse(CatalogueRepository.refreshDue(3, previous, previous + 3_600_000));
    }
}
