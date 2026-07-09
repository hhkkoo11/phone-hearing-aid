package com.daicg.hearingaid;

import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;

final class CallAudioEnhancer {
    private static Equalizer equalizer;
    private static LoudnessEnhancer loudnessEnhancer;

    private CallAudioEnhancer() {
    }

    static synchronized void enable() {
        enableEqualizer();
        enableLoudnessEnhancer();
    }

    static synchronized void disable() {
        if (equalizer != null) {
            try {
                equalizer.setEnabled(false);
                equalizer.release();
            } catch (RuntimeException ignored) {
            }
            equalizer = null;
        }
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setEnabled(false);
                loudnessEnhancer.release();
            } catch (RuntimeException ignored) {
            }
            loudnessEnhancer = null;
        }
    }

    private static void enableEqualizer() {
        try {
            if (equalizer == null) {
                equalizer = new Equalizer(0, 0);
            }
            short[] range = equalizer.getBandLevelRange();
            short min = range[0];
            short max = range[1];
            short bands = equalizer.getNumberOfBands();
            for (short band = 0; band < bands; band++) {
                int centerHz = equalizer.getCenterFreq(band) / 1000;
                short level;
                if (centerHz < 250) {
                    level = clampBand(-900, min, max);
                } else if (centerHz <= 3600) {
                    level = clampBand(650, min, max);
                } else if (centerHz <= 5200) {
                    level = clampBand(250, min, max);
                } else {
                    level = clampBand(-450, min, max);
                }
                equalizer.setBandLevel(band, level);
            }
            equalizer.setEnabled(true);
        } catch (RuntimeException ignored) {
            if (equalizer != null) {
                try {
                    equalizer.release();
                } catch (RuntimeException ignoredRelease) {
                }
                equalizer = null;
            }
        }
    }

    private static void enableLoudnessEnhancer() {
        try {
            if (loudnessEnhancer == null) {
                loudnessEnhancer = new LoudnessEnhancer(0);
            }
            loudnessEnhancer.setTargetGain(650);
            loudnessEnhancer.setEnabled(true);
        } catch (RuntimeException ignored) {
            if (loudnessEnhancer != null) {
                try {
                    loudnessEnhancer.release();
                } catch (RuntimeException ignoredRelease) {
                }
                loudnessEnhancer = null;
            }
        }
    }

    private static short clampBand(int level, short min, short max) {
        return (short) Math.max(min, Math.min(max, level));
    }
}
