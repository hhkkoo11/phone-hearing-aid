package com.daicg.hearingaid;

import java.util.Locale;

enum HeadsetProfile {
    WIRED,
    BLUETOOTH_IN_EAR,
    BLUETOOTH_BONE
}

final class HeadsetProfileDetector {
    private static final String[] BONE_KEYWORDS = {
            "bone", "bone conduction", "openrun", "openmove", "openswim",
            "shokz", "aftershokz", "shockz", "韶音", "骨传导", "骨傳導"
    };

    private HeadsetProfileDetector() {
    }

    static HeadsetProfile detect(boolean wired, CharSequence productName, boolean manualBoneMode) {
        if (wired) {
            return HeadsetProfile.WIRED;
        }
        if (manualBoneMode || isBoneConductionName(productName)) {
            return HeadsetProfile.BLUETOOTH_BONE;
        }
        return HeadsetProfile.BLUETOOTH_IN_EAR;
    }

    static boolean isBoneConductionName(CharSequence productName) {
        if (productName == null) {
            return false;
        }
        String normalized = productName.toString().trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String keyword : BONE_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
