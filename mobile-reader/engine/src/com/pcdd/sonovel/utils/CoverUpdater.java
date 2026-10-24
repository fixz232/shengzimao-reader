package com.pcdd.sonovel.utils;

import com.pcdd.sonovel.model.Rule.Book;

/** Android keeps the source cover; desktop cross-site cover ranking requires virtual threads and AWT. */
public final class CoverUpdater {
    public static String fetchCover(Book book, String coverUrl) {
        String current = coverUrl == null ? "" : coverUrl.trim();
        if (current.isEmpty() && book.getCoverUrl() != null) current = book.getCoverUrl();
        book.setCoverUrl(current);
        return current;
    }

    private CoverUpdater() {}
}
