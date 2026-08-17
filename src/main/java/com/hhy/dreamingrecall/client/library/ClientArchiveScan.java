package com.hhy.dreamingrecall.client.library;

import java.util.List;

public record ClientArchiveScan(List<ClientArchiveEntry> archives, List<String> errors) {
    public ClientArchiveScan {
        archives = List.copyOf(archives);
        errors = List.copyOf(errors);
    }
}
