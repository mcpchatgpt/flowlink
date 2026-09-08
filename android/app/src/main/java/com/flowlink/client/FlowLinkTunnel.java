package com.flowlink.client;

import com.wireguard.android.backend.Tunnel;

final class FlowLinkTunnel implements Tunnel {
    private volatile State state = State.DOWN;
    @Override public String getName() {
        return "flowlink";
    }
    @Override public void onStateChange(State newState) {
        state = newState;
    }
    State state() {
        return state;
    }
}
