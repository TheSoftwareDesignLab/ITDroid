package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;

/** What the preflight refuses before anything is decoded or explored, and says why. */
class PreflightTest {

    @SuppressWarnings("unchecked")
    private static JSONObject app(long minSdk, long targetSdk) {
        JSONObject o = new JSONObject();
        o.put("minSdk", minSdk);
        o.put("targetSdk", targetSdk);
        o.put("launchable", true);
        o.put("trustedWebActivity", false);
        o.put("hasStrings", true);
        return o;
    }

    @Test
    void anAppBuiltForAnOldAndroidIsRefusedWithTheReason() {
        // jamendo and mileage target API 10; SyncMyPix says no targetSdk and targets its minSdk, 8
        for (JSONObject old : List.of(app(9, 10), app(8, -1), app(16, 22))) {
            ITDroidException e = assertThrows(ITDroidException.class, () -> Preflight.checkDecodedApp(old, 33));
            assertTrue(e.getMessage().contains("built for Android 5.1 or older"), e.getMessage());
        }
    }

    @Test
    void anAppBuiltForAndroid6OrLaterGoesOn() {
        assertDoesNotThrow(() -> Preflight.checkDecodedApp(app(16, 24), 33));   // orWall
        assertDoesNotThrow(() -> Preflight.checkDecodedApp(app(26, 37), 36));
        assertDoesNotThrow(() -> Preflight.checkDecodedApp(app(23, -1), 33));
    }

    @Test
    void anAppThatNeedsANewerAndroidThanTheDeviceIsRefused() {
        ITDroidException e = assertThrows(ITDroidException.class, () -> Preflight.checkDecodedApp(app(34, 36), 33));
        assertTrue(e.getMessage().contains("requires API 34"), e.getMessage());
    }

    @Test
    void anArmOnlyAppIsRefusedOnAnX86Device() {
        assertThrows(ITDroidException.class, () -> Preflight.checkAbis(List.of("arm64-v8a"), List.of("x86_64")));
        assertDoesNotThrow(() -> Preflight.checkAbis(List.of("arm64-v8a", "x86"), List.of("x86")));
        assertDoesNotThrow(() -> Preflight.checkAbis(List.of(), List.of("x86_64")));
    }
}
