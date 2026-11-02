package com.example.automodsync;

import java.util.List;

/**
 * Keeps all client-only class references out of the common payload registration
 * path.  Dedicated servers never execute these methods, so the client GUI
 * classes are not loaded on a server.
 */
final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    static void onSyncUrl(String url) {
        ClientSideManager.updateCachedUrl(url);
    }

    static void onServerVersion(int version, int revision, ServerSideManager.VerificationStatus status) {
        ClientSideManager.setServerVersion(version, revision, status);
    }

    static void onVerificationRequest(int candidateVersion, List<String> changedModIds,
                                      List<String> changedServerHashes) {
        ClientSideManager.respondToVerification(candidateVersion, changedModIds, changedServerHashes);
    }

    static void onVerificationComplete(int version, int revision) {
        ClientSideManager.commitAdministratorVerification(version, revision);
    }
}
