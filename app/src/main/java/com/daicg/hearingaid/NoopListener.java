package com.daicg.hearingaid;

final class NoopListener implements HearingEngine.Listener {
    static final NoopListener INSTANCE = new NoopListener();

    private NoopListener() {
    }

    @Override
    public void onLevel(float level) {
    }

    @Override
    public void onError(String message) {
    }

    @Override
    public void onGainReduced(float gain) {
    }

    @Override
    public void onLoudListening(float gain) {
    }
}
